package com.example.hms.enums;

/**
 * Billing plan PR 9 (scorecard M5): the operator a Mobile Money payment went
 * through, so the cash close totals Orange Money and Moov Money apart.
 *
 * <p>There is no operator integration: the cashier records the payment by hand
 * with the operator's transaction reference and checks it against the
 * operator's merchant statement. A row recorded before V308 has no operator
 * ("Opérateur non précisé").
 */
public enum MobileMoneyOperator {
    ORANGE_MONEY,
    MOOV_MONEY,
    /** Another operator than Orange Money or Moov Money ("Autre opérateur"). */
    AUTRE
}
