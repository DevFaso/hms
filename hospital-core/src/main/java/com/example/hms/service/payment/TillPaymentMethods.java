package com.example.hms.service.payment;

import com.example.hms.enums.PaymentMethod;
import com.example.hms.enums.PharmacyPaymentMethod;
import com.example.hms.exception.FieldBusinessException;

/**
 * QA D1611 (round 44): which payment methods are money received at a till.
 *
 * <p>An insurer's share is not money in the drawer: it is owed by the payer
 * and has to be claimed. No till creates such a claim (insurance billing is
 * not built yet), so a payment recorded as INSURANCE counted the insurer's
 * share as collected and it was never claimed. Every entry point that records
 * a payment (visit invoice at the cashier or by the patient, check-in copay,
 * pharmacy dispense checkout, pharmacy counter sale) refuses it here, with
 * the request's field named. The enum constants stay so that rows recorded
 * before this fix still read and print.
 *
 * <p>{@code TillPaymentMethodGuardTest} keeps every payment writer calling this class.
 */
public final class TillPaymentMethods {

    static final String MSG_INSURANCE_NOT_A_PAYMENT = "billing.payment.insuranceNotAPayment";

    private TillPaymentMethods() {
    }

    /** Refuses a visit-invoice or copay method that is not money received now. */
    public static void requireReceivedAtTill(PaymentMethod method, String field) {
        if (method == PaymentMethod.INSURANCE) {
            throw new FieldBusinessException(field, MSG_INSURANCE_NOT_A_PAYMENT);
        }
    }

    /** Refuses a pharmacy method that is not money received now. */
    public static void requireReceivedAtTill(PharmacyPaymentMethod method, String field) {
        if (method == PharmacyPaymentMethod.INSURANCE) {
            throw new FieldBusinessException(field, MSG_INSURANCE_NOT_A_PAYMENT);
        }
    }
}
