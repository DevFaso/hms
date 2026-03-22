package com.example.hms.enums;

/**
 * Status of a nurse-to-nurse handoff report.
 */
public enum NurseHandoffStatus {
    /** Handoff has been created and is pending completion. */
    PENDING,
    /** Handoff acknowledged and completed by the receiving nurse. */
    COMPLETED,
    /** Handoff was cancelled before completion. */
    CANCELLED
}
