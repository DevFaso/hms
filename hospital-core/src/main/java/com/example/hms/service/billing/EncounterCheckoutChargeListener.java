package com.example.hms.service.billing;

import com.example.hms.enums.InvoiceStatus;
import com.example.hms.enums.ItemCategory;
import com.example.hms.event.EncounterCheckedOutEvent;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Encounter;
import com.example.hms.model.EncounterTreatment;
import com.example.hms.model.InvoiceItem;
import com.example.hms.model.LabOrder;
import com.example.hms.model.ProcedureOrder;
import com.example.hms.model.Treatment;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.EncounterTreatmentRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.ProcedureOrderRepository;
import com.example.hms.repository.TreatmentRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.InvoiceNumberAllocator;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.utility.MessageUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * QA D972 (rounds 16 and 17): a checked-out visit reached billing as nothing
 * but its 0-line check-in copay. The clinic publishes
 * {@link EncounterCheckedOutEvent} at checkout (round 16, the producer); this
 * is the consumer: after the checkout commits, it opens one DRAFT invoice for
 * the visit with a line for each billable item, for the cashier to check,
 * price where needed and issue.
 *
 * <ul>
 *   <li>each service recorded on the visit (encounter treatments) at the
 *       hospital's own price for it;</li>
 *   <li>when no service was recorded, one consultation line, priced by the
 *       hospital's single active service named for a consultation, else at
 *       zero for the cashier to price;</li>
 *   <li>each lab order and procedure order not withdrawn (the event already
 *       leaves them out), priced by the hospital's single active service of
 *       the same name, else at zero.</li>
 * </ul>
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
@RequiredArgsConstructor
public class EncounterCheckoutChargeListener {

    private static final List<String> FALLBACK_OWNER_ROLES = List.of("ROLE_BILLING_SPECIALIST", "ROLE_HOSPITAL_ADMIN");
    /** Words a hospital's priced-service name uses for a consultation (EN/FR/ES). */
    private static final List<String> CONSULTATION_WORDS = List.of("consultation", "consulta");

    private final EncounterRepository encounterRepository;
    private final BillingInvoiceRepository invoiceRepository;
    private final EncounterTreatmentRepository encounterTreatmentRepository;
    private final LabOrderRepository labOrderRepository;
    private final ProcedureOrderRepository procedureOrderRepository;
    private final TreatmentRepository treatmentRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final InvoiceNumberAllocator invoiceNumberAllocator;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCheckedOut(EncounterCheckedOutEvent event) {
        try {
            chargeVisit(event);
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
        List<Treatment> priced = treatmentRepository.findByHospital_Id(hospitalId).stream()
            .filter(t -> !Boolean.FALSE.equals(t.getActive()) && t.getPrice() != null && t.getPrice().signum() >= 0
                && t.getName() != null)
            .toList();

        LocalDate today = LocalDate.now(ZoneId.systemDefault());
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
            .notes(MessageUtil.resolve(NotificationLocales.STAFF, "billing.checkout.draftNote",
                event.encounterCode() != null ? event.encounterCode() : encounter.getCode()))
            .build();

        List<EncounterTreatment> services = encounterTreatmentRepository.findByEncounter_Id(encounter.getId());
        for (EncounterTreatment given : services) {
            Treatment treatment = given.getTreatment();
            if (treatment == null) {
                continue;
            }
            invoice = addLine(invoice, line(treatment.getName(), ItemCategory.SERVICE,
                treatment.getPrice(), treatment, owner.get()));
        }
        if (services.isEmpty()) {
            Optional<Treatment> consultation = single(priced, name -> CONSULTATION_WORDS.stream().anyMatch(name::contains));
            invoice = addLine(invoice, line(consultation.map(Treatment::getName)
                    .orElseGet(() -> MessageUtil.resolve(NotificationLocales.STAFF, "billing.checkout.consultation")),
                ItemCategory.SERVICE, consultation.map(Treatment::getPrice).orElse(null),
                consultation.orElse(null), owner.get()));
        }
        for (LabOrder order : labOrderRepository.findAllById(event.labOrderIds())) {
            String test = order.getLabTestDefinition() != null ? order.getLabTestDefinition().getName() : null;
            if (test == null || test.isBlank()) {
                continue;
            }
            Optional<Treatment> service = sameName(priced, test);
            invoice = addLine(invoice, line(test, ItemCategory.LAB_TEST, service.map(Treatment::getPrice).orElse(null),
                service.orElse(null), owner.get()));
        }
        for (ProcedureOrder order : procedureOrderRepository.findAllById(event.procedureOrderIds())) {
            String procedure = order.getProcedureName();
            if (procedure == null || procedure.isBlank()) {
                continue;
            }
            Optional<Treatment> service = sameName(priced, procedure);
            invoice = addLine(invoice, line(procedure, ItemCategory.SERVICE, service.map(Treatment::getPrice).orElse(null),
                service.orElse(null), owner.get()));
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

    private static InvoiceItem line(String description, ItemCategory category, BigDecimal price, Treatment service,
                                    UserRoleHospitalAssignment owner) {
        BigDecimal unit = (price != null ? price : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        String text = description.length() > 255 ? description.substring(0, 255) : description;
        return InvoiceItem.builder()
            .itemDescription(text)
            .quantity(1)
            .itemCategory(category)
            .unitPrice(unit)
            .totalPrice(unit)
            .assignment(owner)
            .relatedService(service)
            .build();
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

    /** The one priced service named exactly like {@code name} (case and accents aside), if exactly one is. */
    private static Optional<Treatment> sameName(List<Treatment> priced, String name) {
        String wanted = fold(name);
        return single(priced, candidate -> candidate.equals(wanted));
    }

    private static Optional<Treatment> single(List<Treatment> priced, java.util.function.Predicate<String> nameMatches) {
        List<Treatment> matches = priced.stream().filter(t -> nameMatches.test(fold(t.getName()))).toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private static String fold(String text) {
        return Normalizer.normalize(text.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");
    }
}
