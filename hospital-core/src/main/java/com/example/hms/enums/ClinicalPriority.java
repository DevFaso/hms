package com.example.hms.enums;

/**
 * The urgency a lab order, an ultrasound order or a nurse task is placed
 * with. Stored as the code (the columns are text); the worklists, the triage
 * queue and the nurse task filter key on these three.
 */
public enum ClinicalPriority {
    ROUTINE,
    URGENT,
    STAT;

    /**
     * QA D1370 (round 31): the edge check for a request field - a code in any
     * case, or blank (an absent priority keeps the order's own, ROUTINE for a
     * new one). {@code FreeTextStatusRequestFieldGuardTest} keeps it equal to {@link #values()}.
     */
    public static final String CODES = "\\s*+(?:ROUTINE|URGENT|STAT)?\\s*+";
}
