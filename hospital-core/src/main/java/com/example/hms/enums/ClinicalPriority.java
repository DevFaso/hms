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

    /**
     * QA D1538 (round 40): an order placed URGENT or STAT is an emergency. A rule that would refuse the
     * order on what the patient record says (ObstetricEligibility) never refuses an emergency: the care is
     * not delayed, a record that contradicts it is corrected afterwards. Provisional, pending clinical approval.
     */
    public boolean isEmergency() {
        return this == URGENT || this == STAT;
    }

    /** The priority a stored or requested code names, in any case and padding; null when blank or unknown. */
    public static ClinicalPriority parse(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String value = code.trim().toUpperCase(java.util.Locale.ROOT);
        for (ClinicalPriority priority : values()) {
            if (priority.name().equals(value)) {
                return priority;
            }
        }
        return null;
    }

    /** Whether a code names an emergency priority ({@link #isEmergency()}); blank or unknown is not one. */
    public static boolean isEmergency(String code) {
        ClinicalPriority priority = parse(code);
        return priority != null && priority.isEmergency();
    }
}
