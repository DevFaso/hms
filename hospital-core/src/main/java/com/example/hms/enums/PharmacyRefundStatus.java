package com.example.hms.enums;

/**
 * QA D2676 (r65, V311; billing-money plan PR 13): the life of a pharmacy refund. REQUESTED with a reason, APPROVED
 * or REJECTED by another hospital admin or accountant (owner decision D-3), PAID when handed to the patient, or
 * CANCELLED before payment.
 */
public enum PharmacyRefundStatus {
    REQUESTED,
    APPROVED,
    REJECTED,
    PAID,
    CANCELLED
}
