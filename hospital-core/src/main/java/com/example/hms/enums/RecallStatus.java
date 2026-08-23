package com.example.hms.enums;

/** Lifecycle of a patient recall (P3 #22). Close/cancel, never delete. */
public enum RecallStatus {
    /** Due at some future date; the sweep will notify as it approaches. */
    PENDING,
    /** The patient has been notified; the desk follows up. */
    NOTIFIED,
    /** An appointment was booked against the recall. */
    SCHEDULED,
    /** Completed — the visit happened or the need lapsed. */
    CLOSED,
    /** Withdrawn — recall was created in error or is no longer wanted. */
    CANCELLED
}
