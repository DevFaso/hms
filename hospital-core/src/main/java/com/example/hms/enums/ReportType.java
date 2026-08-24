package com.example.hms.enums;

/**
 * Canned scheduled-report types (P3 #25a). Every type is AGGREGATE-ONLY
 * — counts per day or per code, never patient rows: reports leave the
 * system as email attachments, and email is an untrusted channel that
 * must never carry PHI (the recall-SMS stance).
 */
public enum ReportType {
    /** Encounters per day: total / completed / cancelled. */
    ENCOUNTER_ACTIVITY,
    /** Appointments per day: total / completed / cancelled / no-show. */
    APPOINTMENT_ACTIVITY,
    /**
     * Diagnoses recorded in the period, ranked by count — the monthly
     * morbidity picture ("most treated diseases"). Rows are
     * (rank, ICD code, display, count); source is the problem list
     * ({@code clinical.patient_problems}), windowed on {@code createdAt}
     * because that is when this hospital treated it — {@code onsetDate}
     * is patient-reported, nullable, and can predate the period by years.
     */
    TOP_DIAGNOSES
}
