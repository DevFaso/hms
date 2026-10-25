package com.example.hms.enums;

/**
 * QA D788: how a visit no clinician saw is closed. Either way the encounter
 * becomes CANCELLED and is never counted as a consultation.
 */
public enum UnseenVisitOutcome {
    /** The patient arrived and left before a clinician saw them. */
    LEFT_WITHOUT_BEING_SEEN,
    /** The visit was called off (registered in error, rebooked, ...). */
    CANCELLED
}
