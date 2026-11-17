package com.example.hms.enums;

/**
 * Where a ledger row stands (QA D140, D142). Only CONFIRMED rows are counted
 * in an invoice's amount paid.
 */
public enum PaymentStatus {
    /** Declared by the patient; waits for a cashier to confirm the money arrived. */
    PENDING,
    /** Counted in the invoice's amount paid. */
    CONFIRMED,
    /** A pending declaration staff refused; never counted. */
    REJECTED,
    /** Confirmed, then reversed by billing staff with a reason; no longer counted. */
    REVERSED
}
