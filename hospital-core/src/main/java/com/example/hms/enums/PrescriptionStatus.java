package com.example.hms.enums;

public enum PrescriptionStatus {
    DRAFT,
    PENDING_SIGNATURE,
    SIGNED,
    TRANSMITTED,
    TRANSMISSION_FAILED,
    CANCELLED,
    DISCONTINUED,
    /** Prescription flagged by pharmacy and awaiting physician clarification. */
    PENDING_CLARIFICATION
}
