package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.PharmacyPaymentStatus;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.PharmacyPayment;
import com.example.hms.repository.pharmacy.PharmacyPaymentRepository;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * QA D404: the one owner of what happens to the money when a dispense is
 * cancelled. Every cancel path (the pharmacist's cancel of a fill, the
 * pharmacist's cancel of a preparation, the prescriber's withdrawal or edit
 * that voids a preparation) calls it inside its own transaction, so the
 * stock return and the void commit or roll back together.
 *
 * <p>A POSTED payment becomes VOIDED, stamped with who, when and why, and a
 * {@link AuditEventType#PAYMENT_REVERSED} row is written for each: the till
 * then owes the patient that amount back, and the patient's history shows
 * the payment as voided instead of counted. Billing-money plan PR 8: it was
 * written as REFUND_ISSUED, but a void hands nothing back by itself; that
 * event now means money actually paid out on an invoice refund.
 */
@Component
@RequiredArgsConstructor
public class PharmacyPaymentVoider {

    private final PharmacyPaymentRepository paymentRepository;
    private final PharmacyServiceSupport support;
    private final RoleValidator roleValidator;
    private final Clock clock;

    /** Void the counted payments of {@code dispense}; 0 when it was unpaid. */
    public int voidForCancelledDispense(Dispense dispense, String reason) {
        List<PharmacyPayment> posted =
                paymentRepository.findByDispense_IdAndStatus(dispense.getId(), PharmacyPaymentStatus.POSTED);
        if (posted.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        java.util.UUID voidedBy = roleValidator.getCurrentUserId();
        int voided = paymentRepository.voidPostedForDispense(dispense.getId(), voidedBy, reason, now);
        for (PharmacyPayment payment : posted) {
            support.logAudit(AuditEventType.PAYMENT_REVERSED,
                    // Ids only (PR 8 review): the amount stays on the payment row.
                    "Pharmacy payment " + payment.getId() + " voided for cancelled dispense " + dispense.getId(),
                    payment.getId().toString(), "PHARMACY_PAYMENT");
            // Keep a managed copy honest for the rest of the transaction.
            payment.setStatus(PharmacyPaymentStatus.VOIDED);
            payment.setVoidedAt(now);
            payment.setVoidedBy(voidedBy);
            payment.setVoidReason(reason);
        }
        return voided;
    }
}
