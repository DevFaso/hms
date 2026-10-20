package com.example.hms.service;

import com.example.hms.enums.DeliveryMode;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.enums.ItemCategory;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.InvoiceItem;
import com.example.hms.model.Treatment;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.TreatmentRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.utility.MessageUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * QA D516 (D420 / D355 / D225 / D163 / D105): a recorded delivery posted no
 * charge, so a birth never reached billing. Recording one now opens a DRAFT
 * invoice for the mother at the delivering hospital, with one SERVICE line for
 * the delivery. The line takes the price of the hospital's own priced service
 * for the delivery mode (caesarean or vaginal), when exactly one active
 * service of the catalog names it; otherwise it is posted at zero for the
 * billing clerk to price, so the charge is never lost. It runs in its own
 * transaction, after the delivery commits: a failed charge never rolls the
 * birth back. One delivery per labour episode, so one charge.
 */
@Service
@RequiredArgsConstructor
public class DeliveryChargeService {

    private static final Set<DeliveryMode> CAESAREAN =
        Set.of(DeliveryMode.CAESAREAN_ELECTIVE, DeliveryMode.CAESAREAN_EMERGENCY);
    /** Words a hospital's priced-service name uses for a caesarean (EN/FR/ES). */
    private static final List<String> CAESAREAN_WORDS = List.of("cesarean", "caesarean", "césarienne", "cesarienne", "cesárea", "cesarea");
    /** Words a hospital's priced-service name uses for a delivery (EN/FR/ES). */
    private static final List<String> DELIVERY_WORDS = List.of("delivery", "accouchement", "parto");

    private final BillingInvoiceRepository invoiceRepository;
    private final TreatmentRepository treatmentRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final InvoiceNumberAllocator invoiceNumberAllocator;

    /**
     * Posts the delivery charge. {@code recorderUserId} is the clinician who
     * recorded the birth; the line is filed under their assignment at the
     * hospital. Returns the draft invoice, or empty when the recorder holds no
     * active assignment there (nothing can own the line).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<BillingInvoice> chargeDelivery(DeliveryRecord delivery, UUID recorderUserId) {
        if (delivery == null || delivery.getHospital() == null || delivery.getPatient() == null
                || recorderUserId == null) {
            return Optional.empty();
        }
        UUID hospitalId = delivery.getHospital().getId();
        Optional<UserRoleHospitalAssignment> assignment =
            assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(recorderUserId, hospitalId);
        if (assignment.isEmpty()) {
            return Optional.empty();
        }
        boolean caesarean = delivery.getDeliveryMode() != null && CAESAREAN.contains(delivery.getDeliveryMode());
        Optional<Treatment> service = pricedService(hospitalId, caesarean);
        BigDecimal price = service.map(Treatment::getPrice).orElse(BigDecimal.ZERO)
            .setScale(2, RoundingMode.HALF_UP);

        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        BillingInvoice invoice = BillingInvoice.builder()
            .patient(delivery.getPatient())
            .hospital(delivery.getHospital())
            .invoiceNumber(invoiceNumberAllocator.next(delivery.getHospital(), today))
            .invoiceDate(today)
            .dueDate(today.plusDays(30))
            .totalAmount(BigDecimal.ZERO)
            .amountPaid(BigDecimal.ZERO)
            .status(InvoiceStatus.DRAFT)
            .createdBy(recorderUserId)
            .build();
        InvoiceItem line = InvoiceItem.builder()
            .itemDescription(MessageUtil.resolveOrRaw(caesarean
                ? "maternity.delivery.charge.caesarean" : "maternity.delivery.charge.vaginal"))
            .quantity(1)
            .itemCategory(ItemCategory.SERVICE)
            .unitPrice(price)
            .totalPrice(price)
            .assignment(assignment.get())
            .relatedService(service.orElse(null))
            .build();
        invoice.addItem(line);
        // addItem keeps a priced draft a DRAFT; a zero line must stay one too.
        invoice.setStatus(InvoiceStatus.DRAFT);
        return Optional.of(invoiceRepository.save(invoice));
    }

    /** The one active priced service of the hospital that names this kind of delivery, if exactly one does. */
    private Optional<Treatment> pricedService(UUID hospitalId, boolean caesarean) {
        List<Treatment> matches = treatmentRepository.findByHospital_Id(hospitalId).stream()
            .filter(t -> !Boolean.FALSE.equals(t.getActive()) && t.getPrice() != null
                && t.getPrice().signum() >= 0 && t.getName() != null)
            .filter(t -> {
                String name = t.getName().toLowerCase(Locale.ROOT);
                boolean namesCaesarean = CAESAREAN_WORDS.stream().anyMatch(name::contains);
                if (caesarean) {
                    return namesCaesarean;
                }
                return !namesCaesarean && DELIVERY_WORDS.stream().anyMatch(name::contains);
            })
            .toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }
}
