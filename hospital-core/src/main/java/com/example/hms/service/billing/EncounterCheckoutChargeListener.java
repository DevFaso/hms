package com.example.hms.service.billing;

import com.example.hms.enums.InvoiceLinePriceSource;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.event.EncounterCheckedOutEvent;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Encounter;
import com.example.hms.model.InvoiceItem;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.TreatmentRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.InvoiceNumberAllocator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * QA D972 (rounds 16, 17 and 18): a checked-out visit reached billing as
 * nothing but its 0-line check-in copay. The clinic publishes
 * {@link EncounterCheckedOutEvent} when a visit is completed (checkout, a
 * completed PUT, a discharge, the admission that ends an emergency visit, a
 * death); this is the consumer: after the visit closes, it opens one DRAFT
 * invoice for it with a line for each billable item, for the cashier to check,
 * price where needed and issue.
 *
 * <p>The lines come from the registry of billable order types
 * ({@link BillableOrderType}): every type charged on the visit invoice has one
 * {@link VisitChargeSource} (consultation or recorded services, lab, imaging,
 * procedure, transfusion, bed days), and the listener asks every source.
 * Round 17 listed the order types here by hand and left imaging out; a type
 * with no source now fails {@code BillableOrderRegistryGuardTest}.
 *
 * <p>Medicines are NOT put on the visit invoice: a dispensed medicine is paid
 * at the pharmacy checkout (its own payment, QA D264), so charging it here
 * would bill it twice. A zero-priced line is never a guessed price: it keeps
 * the charge from being lost while the cashier prices it, the rule
 * {@code DeliveryChargeService} set (QA D516).
 *
 * <p>When issued, the visit invoice takes the copay paid at check-in (QA D210),
 * so the patient is not billed twice for it. One visit invoice per visit: a
 * visit that already has a live invoice other than its copay is left alone
 * (a re-delivered event, or a cashier who already billed it).
 *
 * <p>Runs after the checkout commits, in its own transaction, re-reading by id
 * what it prices: a failed charge never rolls the checkout back, and is logged
 * (ids only).
 */
@Slf4j
@Service
public class EncounterCheckoutChargeListener {

    static final String DRAFT_NOTE_CODE = "billing.checkout.draftNote";

    private static final List<String> FALLBACK_OWNER_ROLES = List.of("ROLE_BILLING_SPECIALIST", "ROLE_HOSPITAL_ADMIN");

    private final EncounterRepository encounterRepository;
    private final BillingInvoiceRepository invoiceRepository;
    private final TreatmentRepository treatmentRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final InvoiceNumberAllocator invoiceNumberAllocator;
    private final TariffResolver tariffResolver;
    /** One per visit-invoice type of the registry, in the registry's order. */
    private final List<VisitChargeSource> sources;

    public EncounterCheckoutChargeListener(EncounterRepository encounterRepository,
                                           BillingInvoiceRepository invoiceRepository,
                                           TreatmentRepository treatmentRepository,
                                           UserRoleHospitalAssignmentRepository assignmentRepository,
                                           InvoiceNumberAllocator invoiceNumberAllocator,
                                           TariffResolver tariffResolver,
                                           List<VisitChargeSource> sources) {
        this.encounterRepository = encounterRepository;
        this.invoiceRepository = invoiceRepository;
        this.treatmentRepository = treatmentRepository;
        this.assignmentRepository = assignmentRepository;
        this.invoiceNumberAllocator = invoiceNumberAllocator;
        this.tariffResolver = tariffResolver;
        this.sources = sources.stream()
            .sorted(java.util.Comparator.comparing(VisitChargeSource::type))
            .toList();
        java.util.Set<BillableOrderType> covered = java.util.EnumSet.noneOf(BillableOrderType.class);
        this.sources.forEach(source -> covered.add(source.type()));
        for (BillableOrderType type : BillableOrderType.values()) {
            if (type.onVisitInvoice() && !covered.contains(type)) {
                log.error("Billable order type {} has no charge source: its items are not billed at checkout", type);
            }
        }
    }

    /** The charge's own transaction; null in a hand-built unit test (the charge then runs as called). */
    private org.springframework.transaction.support.TransactionTemplate chargeTx;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTransactionManager(org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.chargeTx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.chargeTx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Not transactional itself: the charge runs in its own REQUIRES_NEW
     * transaction ({@code chargeTx}), so a failure at commit (a constraint, a
     * rollback-only mark) is caught here too and logged with ids only, never
     * with row values.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCheckedOut(EncounterCheckedOutEvent event) {
        try {
            if (chargeTx == null) {
                chargeVisit(event);
            } else {
                chargeTx.executeWithoutResult(status -> chargeVisit(event));
            }
        } catch (RuntimeException ex) {
            log.warn("Visit {} was not billed at checkout: {}", event != null ? event.encounterId() : null,
                ex.getClass().getSimpleName());
        }
    }

    /** The visit's draft invoice, or empty when there is nothing to do (logged). */
    public Optional<BillingInvoice> chargeVisit(EncounterCheckedOutEvent event) {
        if (event == null || event.encounterId() == null || event.hospitalId() == null) {
            return Optional.empty();
        }
        Encounter encounter = encounterRepository.findByIdAndHospital_Id(event.encounterId(), event.hospitalId())
            .orElse(null);
        if (encounter == null || encounter.getPatient() == null || encounter.getHospital() == null) {
            return Optional.empty();
        }
        // One visit invoice per visit: the copay is folded into it when it is issued.
        boolean alreadyBilled = invoiceRepository
            .findByEncounter_IdAndStatusNot(encounter.getId(), InvoiceStatus.CANCELLED).stream()
            .anyMatch(existing -> !existing.isCheckInCopay());
        if (alreadyBilled) {
            log.info("Visit {} already has an invoice; checkout did not open another", encounter.getId());
            return Optional.empty();
        }
        UUID hospitalId = encounter.getHospital().getId();
        Optional<UserRoleHospitalAssignment> owner = ownerOf(encounter, hospitalId);
        if (owner.isEmpty()) {
            log.warn("Visit {} at hospital {} was not billed: no active assignment can own the lines",
                encounter.getId(), hospitalId);
            return Optional.empty();
        }
        VisitPriceList prices = new VisitPriceList(treatmentRepository.findByHospital_Id(hospitalId));
        java.time.LocalDateTime checkedOutAt = event.checkedOutAt() != null
            ? event.checkedOutAt() : encounter.getCheckoutTimestamp();
        // Billing-money plan PR 3 (D-4): the price list of the visit's own hospital, as it stood on the checkout
        // day (Africa/Ouagadougou); a price that starts tomorrow never prices today's visit.
        TariffResolver.Tariffs tariffs = new TariffResolver.Tariffs(tariffResolver, hospitalId,
            tariffResolver.businessDayOf(checkedOutAt));
        VisitChargeSource.Visit visit = new VisitChargeSource.Visit(encounter, checkedOutAt, prices, tariffs);

        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        // QA D1901: the note is kept as its code and the visit's code, shown
        // in each reader's language; notes holds the French copy.
        BillingText note = BillingText.of(DRAFT_NOTE_CODE,
            BillingText.value(event.encounterCode() != null ? event.encounterCode() : encounter.getCode()));
        BillingInvoice invoice = BillingInvoice.builder()
            .patient(encounter.getPatient())
            .hospital(encounter.getHospital())
            .encounter(encounter)
            .invoiceNumber(invoiceNumberAllocator.next(encounter.getHospital(), today))
            .invoiceDate(today)
            .dueDate(today.plusDays(30))
            .totalAmount(BigDecimal.ZERO)
            .amountPaid(BigDecimal.ZERO)
            .status(InvoiceStatus.DRAFT)
            .notes(note.french()).notesCode(note.code()).notesArgs(note.argsJson())
            .build();

        for (VisitChargeSource source : sources) {
            if (!source.type().onVisitInvoice()) {
                continue;
            }
            for (VisitChargeSource.VisitCharge charge : source.charges(visit)) {
                invoice = addLine(invoice, line(charge, source.type(), owner.get()));
            }
        }
        // addItem keeps a priced draft a DRAFT; the cashier issues it.
        invoice.setStatus(InvoiceStatus.DRAFT);
        BillingInvoice saved = invoiceRepository.save(invoice);
        log.info("Visit {} checked out: draft invoice {} with {} line(s)", encounter.getId(), saved.getId(),
            saved.getInvoiceItems() != null ? saved.getInvoiceItems().size() : 0);
        return Optional.of(saved);
    }

    /**
     * The invoice keeps its lines in a hash set and a new line has no id yet,
     * so two unsaved lines are one to the set. The invoice is saved after each
     * line (the cascade gives the line its id) before the next one is added.
     */
    private BillingInvoice addLine(BillingInvoice invoice, InvoiceItem line) {
        invoice.addItem(line);
        return invoiceRepository.save(invoice);
    }

    static InvoiceItem line(VisitChargeSource.VisitCharge charge, BillableOrderType type,
                            UserRoleHospitalAssignment owner) {
        BigDecimal unit = (charge.unitPrice() != null ? charge.unitPrice() : BigDecimal.ZERO)
            .setScale(2, RoundingMode.HALF_UP);
        String description = charge.description();
        String text = description.length() > 255 ? description.substring(0, 255) : description;
        BillingText worded = charge.text();
        TariffResolver.Tariff tariff = charge.tariff();
        return InvoiceItem.builder()
            .itemDescription(text)
            // QA D1901: a line the server worded keeps its code (shown in each
            // reader's language); a service or order name has none.
            .textCode(worded != null ? worded.code() : null)
            .textArgs(worded != null ? worded.argsJson() : null)
            .quantity(charge.quantity())
            .itemCategory(charge.category())
            .unitPrice(unit)
            .totalPrice(unit.multiply(BigDecimal.valueOf(charge.quantity())))
            .assignment(owner)
            .relatedService(charge.service())
            // PR 3: where the price came from; a tariff line names the version it copied (D-4).
            .priceSource(priceSourceOf(charge))
            .priceListItemId(tariff != null ? tariff.itemId() : null)
            .priceListPriceId(tariff != null ? tariff.priceId() : null)
            // QA D3603 (r71): the category a plan rule sees, whatever the price source turns out to be.
            .coverageCategory(charge.coverageCategory() != null ? charge.coverageCategory() : type.coverageCategory())
            .build();
    }

    /** The price list first, then a service of the same name, else nothing: the cashier prices it. */
    static InvoiceLinePriceSource priceSourceOf(VisitChargeSource.VisitCharge charge) {
        if (charge.tariff() != null) {
            return InvoiceLinePriceSource.TARIFF;
        }
        return charge.service() != null && charge.unitPrice() != null
            ? InvoiceLinePriceSource.SERVICE_NAME : InvoiceLinePriceSource.UNPRICED;
    }

    /** The treating clinician's assignment here, else the hospital's billing desk or administrator. */
    private Optional<UserRoleHospitalAssignment> ownerOf(Encounter encounter, UUID hospitalId) {
        UUID clinicianUserId = encounter.getStaff() != null && encounter.getStaff().getUser() != null
            ? encounter.getStaff().getUser().getId() : null;
        Optional<UserRoleHospitalAssignment> owner = clinicianUserId == null ? Optional.empty()
            : assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(clinicianUserId, hospitalId);
        if (owner.isEmpty()) {
            owner = assignmentRepository.findFirstByHospital_IdAndRole_CodeInAndActiveTrueOrderByCreatedAtAsc(
                hospitalId, FALLBACK_OWNER_ROLES);
        }
        return owner;
    }
}
