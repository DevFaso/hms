package com.example.hms.enums;

/**
 * Canned scheduled-report types (P3 #25a). Every type is AGGREGATE-ONLY
 * — counts per day, never patient rows: reports leave the system as
 * email attachments, and email is an untrusted channel that must never
 * carry PHI (the recall-SMS stance).
 */
public enum ReportType {
    /** Encounters per day: total / completed / cancelled. */
    ENCOUNTER_ACTIVITY,
    /** Appointments per day: total / completed / cancelled / no-show. */
    APPOINTMENT_ACTIVITY
}
