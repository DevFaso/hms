package com.example.hms.enums;

/**
 * Lifecycle of an in-app transfer order (Tier 2 item 30).
 *
 * <p>Two steps rather than one, because a transfer is ordered by one person
 * and carried out by another some time later. Between the two the destination
 * bed is held {@code RESERVED}, which is what stops the ward clerk allocating
 * it to somebody else in the meantime.
 */
public enum TransferOrderStatus {

    /** Ordered, destination reserved, patient not yet moved. */
    REQUESTED,

    /** The move has happened and the bed invariant has been updated. */
    COMPLETED,

    /** Called off. The reservation is released back to AVAILABLE. */
    CANCELLED
}
