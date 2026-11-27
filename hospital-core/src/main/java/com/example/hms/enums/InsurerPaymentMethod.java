package com.example.hms.enums;

/**
 * Insurer claims plan PR 3 (V456): how a payer remitted money. Never a card, and never a till method: an insurer
 * payment is recorded by finance, outside any cash session, even when it came in cash.
 */
public enum InsurerPaymentMethod {
    BANK_TRANSFER,
    CHEQUE,
    MOBILE_MONEY,
    CASH
}
