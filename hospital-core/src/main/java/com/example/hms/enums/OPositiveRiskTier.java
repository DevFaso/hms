package com.example.hms.enums;

/**
 * The tier of a patient for an emergency release of O RhD positive red cells (V380, rule set
 * EMERGENCY_O_POSITIVE_AUTHORITY). It decides who may take the decision and what review follows.
 */
public enum OPositiveRiskTier {
    /** A man of 18 or more, or a woman above the age cut-off of the hospital, under its active protocol. */
    LOW_RISK,
    /** A woman at or below the cut-off, a girl, a boy, an unknown sex or age, or a hospital with no protocol. */
    HIGH_RISK,
    /** A known anti-D or other clinically significant antibody. */
    KNOWN_ANTIBODY
}
