package com.example.hms.enums;

/**
 * The compliance reviewer's sign-off on a break-the-glass session (E8 #54).
 * Stored as text on {@code clinical.break_glass_sessions.review_outcome}.
 */
public enum BreakGlassReviewOutcome {
    /** The stated reason justified the access. */
    JUSTIFIED,
    /** The access was not justified; the hospital follows its own procedure from here. */
    NOT_JUSTIFIED,
    /** The reviewer needs more from the declaring user before deciding. */
    FOLLOW_UP
}
