package com.example.hms.enums;

/**
 * Billing-money plan (section 3.2, V307 for refunds; V308 adds it to payments): the mobile-money operator that
 * carried the money, so the till totals Orange Money and Moov Money apart. AUTRE is any other operator.
 */
public enum MobileMoneyOperator {
    ORANGE_MONEY,
    MOOV_MONEY,
    AUTRE;

    /** The edge check for a request's operator: a code in any case, or blank (left to the required rule). */
    public static final String CODES = "\\s*+(?:ORANGE_MONEY|MOOV_MONEY|AUTRE)?\\s*+";
}
