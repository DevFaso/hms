package com.example.hms.service.payment;

import com.example.hms.enums.MobileMoneyOperator;
import com.example.hms.enums.PaymentMethod;
import com.example.hms.enums.PharmacyPaymentMethod;
import com.example.hms.exception.FieldBusinessException;

/**
 * Billing plan PR 9 (scorecard M5): which operator a Mobile Money payment
 * went through, so the cash close totals Orange Money and Moov Money apart.
 *
 * <p>There is no operator integration (plan section 1.5): the payment is
 * recorded by hand with the operator's transaction reference. Every staff
 * entry point that records a payment (visit invoice at the cashier, check-in
 * copay, pharmacy dispense checkout, pharmacy counter sale) calls
 * {@code require}: a Mobile Money payment without an operator is refused with
 * the request's field named. A patient's own declaration calls {@code keep}:
 * the mobile apps do not send an operator yet (standing debt), so it is
 * stored as "not stated" rather than refused. Any other method stores no
 * operator.
 *
 * <p>{@code TillPaymentMethodGuardTest} keeps every payment writer calling this class.
 */
public final class MobileMoneyOperators {

    static final String MSG_OPERATOR_REQUIRED = "billing.payment.operatorRequired";

    private MobileMoneyOperators() {
    }

    /** The operator to store for a staff-recorded payment; refused when Mobile Money has none. */
    public static MobileMoneyOperator require(PaymentMethod method, MobileMoneyOperator operator, String field) {
        return require(method == PaymentMethod.MOBILE_MONEY, operator, field);
    }

    /** The operator to store for a staff-recorded pharmacy payment or sale; refused when Mobile Money has none. */
    public static MobileMoneyOperator require(PharmacyPaymentMethod method, MobileMoneyOperator operator, String field) {
        return require(method == PharmacyPaymentMethod.MOBILE_MONEY, operator, field);
    }

    /** The operator to store for a patient's declaration: kept for Mobile Money, never required. */
    public static MobileMoneyOperator keep(PaymentMethod method, MobileMoneyOperator operator) {
        return method == PaymentMethod.MOBILE_MONEY ? operator : null;
    }

    private static MobileMoneyOperator require(boolean mobileMoney, MobileMoneyOperator operator, String field) {
        if (!mobileMoney) {
            return null;
        }
        if (operator == null) {
            throw new FieldBusinessException(field, MSG_OPERATOR_REQUIRED);
        }
        return operator;
    }
}
