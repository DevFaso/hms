package com.example.hms.enums;

/**
 * Status lifecycle for patient-initiated medication refill requests.
 */
public enum RefillStatus {
    /** Patient submitted the request; awaiting provider review. */
    REQUESTED,
    /** Provider approved the refill. */
    APPROVED,
    /** Provider denied the refill (e.g. medication discontinued). */
    DENIED,
    /** Pharmacy has dispensed the medication. */
    DISPENSED,
    /** Patient cancelled the request before it was processed. */
    CANCELLED
}
