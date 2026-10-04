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
    OTHER
}
