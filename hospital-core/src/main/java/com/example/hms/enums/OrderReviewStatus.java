package com.example.hms.enums;

/** Where a row of the order review stands. Only a clinician's decision moves it; no dispute outcome does. */
public enum OrderReviewStatus {
    /** Refused at every use until a prescriber decides. */
    HELD,
    /** Reconfirmed unchanged by a prescriber, who stated why. */
    CONTINUED,
    /** Closed, and linked to the new order that replaces it. */
    REPLACED,
    /** Stopped through the order's own stop path. */
    STOPPED,
    /** A running treatment, not held, waiting for the treating clinician's assessment. */
    ASSESSMENT_DUE,
    /** The treating clinician has recorded their assessment. */
    ASSESSED
}
