package com.example.hms.enums;

/**
 * Lifecycle states for generalized imaging orders.
 */
public enum ImagingOrderStatus {
    DRAFT,
    ORDERED,
    PENDING_AUTHORIZATION,
    SCHEDULED,
    IN_PROGRESS,
    COMPLETED,
    RESULTS_AVAILABLE,
    CANCELLED
}
