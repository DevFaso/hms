package com.example.hms.enums;

/** How soon the biologist reviews an emergency O RhD positive release (V380). */
public enum OPositiveReviewPriority {
    /** The low-risk tier: an entry in the review queue. */
    ROUTINE,
    /** The high-risk tier: within the review deadline of the hospital. */
    URGENT,
    /** A known antibody: the biologist is alerted at once. */
    IMMEDIATE
}
