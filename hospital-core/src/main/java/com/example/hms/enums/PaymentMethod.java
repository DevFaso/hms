package com.example.hms.enums;

public enum PaymentMethod {
    CASH,
    CREDIT_CARD,
    DEBIT_CARD,
    INSURANCE,
    BANK_TRANSFER,
    CHECK,
    /**
     * A card whose credit/debit kind the payer did not say — what the patient
     * portal and both apps offer ("Credit / Debit Card").
     */
    CARD,
    MOBILE_MONEY,
    OTHER;

    /**
     * QA D1363 (round 31): the edge check for a payment request's method - a
     * code in any case, or blank (left to the field's required rule), so an
     * unsupported method is named with a malformed amount in one refusal.
     * {@code FreeTextStatusRequestFieldGuardTest} keeps it equal to {@link #values()}.
     */
    public static final String CODES =
        "\\s*+(?:CASH|CREDIT_CARD|DEBIT_CARD|INSURANCE|BANK_TRANSFER|CHECK|CARD|MOBILE_MONEY|OTHER)?\\s*+";
}
