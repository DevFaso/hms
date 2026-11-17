package com.example.hms.enums;

/**
 * QA D788: how a visit no clinician saw is closed. The encounter becomes
 * {@link EncounterStatus#LEFT_WITHOUT_BEING_SEEN} or
 * {@link EncounterStatus#CANCELLED} (round 13: LWBS is its own status) and is
 * never counted as a consultation.
 */
public enum UnseenVisitOutcome {
    /** The patient arrived and left before a clinician saw them. */
    LEFT_WITHOUT_BEING_SEEN,
    /** The visit was called off (registered in error, rebooked, ...). */
    CANCELLED
}
