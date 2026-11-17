package com.example.hms.enums;

/** What a row of the order review is about. */
public enum OrderReviewType {
    /** A prescription: its prepared fills and pending doses are held with it. */
    PRESCRIPTION,
    /** A blood request: its issued bags are held with it. */
    TRANSFUSION_REQUEST,
    /** A transfusion already running: never held, flagged until a clinician records an assessment. */
    RUNNING_TRANSFUSION
}
