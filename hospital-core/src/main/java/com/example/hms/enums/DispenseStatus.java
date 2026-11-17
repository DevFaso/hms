package com.example.hms.enums;

/**
 * Status of one fill ({@code clinical.dispenses.status}).
 */
public enum DispenseStatus {
    /**
     * Prepared and waiting for collection (G15): the stock is set aside, the
     * patient has been told, the hand-over has not happened. Not a fill: it
     * counts in no dispensed-to-date sum and does not move the prescription
     * status. Only the ready-for-collection actions write it; at most one
     * per prescription (V178 partial unique index).
     */
    PENDING,
    COMPLETED,
    PARTIAL,
    CANCELLED
}
