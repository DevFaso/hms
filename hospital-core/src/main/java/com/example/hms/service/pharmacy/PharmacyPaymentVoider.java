package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.PharmacyPaymentStatus;
import com.example.hms.enums.PharmacyRefundStatus;
import com.example.hms.exception.ConflictException;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.PharmacyPayment;
import com.example.hms.repository.pharmacy.PharmacyPaymentRepository;
import com.example.hms.repository.pharmacy.PharmacyRefundRepository;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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
 *
 * <p>Review of #1239 (money back twice): a void gives the WHOLE payment back. A fill that already has a pharmacy
 * refund asked, approved or paid would give that part back twice (500 F in, 250 F refunded, then 500 F voided), so
 * its payment is never voided: the cancel is refused and the rest goes back as a return and a refund. Checked here,
 * the one place every cancel path passes, under the dispense row lock a refund request also takes.
 */
@Component
@RequiredArgsConstructor
public class PharmacyPaymentVoider {

    /** Asked, approved or paid: each holds part of the payment a void would give back whole. */
    static final Set<PharmacyRefundStatus> LIVE_REFUNDS = EnumSet.of(PharmacyRefundStatus.REQUESTED,
            PharmacyRefundStatus.APPROVED, PharmacyRefundStatus.PAID);
    static final String HAS_REFUND = "pharmacy.dispense.cancelHasRefund";

    private final PharmacyPaymentRepository paymentRepository;
    private final PharmacyRefundRepository refundRepository;
    private final PharmacyServiceSupport support;
    private final RoleValidator roleValidator;
    private final Clock clock;

    /**
     * Refuses (409) while {@code dispense} carries a live pharmacy refund. The caller holds the dispense row lock,
     * so a refund cannot be asked between this check and the void.
     */
    public void requireNoLiveRefund(Dispense dispense) {
        if (refundRepository.existsByDispenseIdAndStatusIn(dispense.getId(), LIVE_REFUNDS)) {
            throw new ConflictException(MessageUtil.resolve(HAS_REFUND));
        }
    }

    /** Void the counted payments of {@code dispense}; 0 when it was unpaid. Refused while a refund is live. */
    public int voidForCancelledDispense(Dispense dispense, String reason) {
        requireNoLiveRefund(dispense);
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
