package com.example.hms.enums;

/** Why a patient is in their {@link OPositiveRiskTier}: what the release row keeps and the screen says. */
public enum OPositiveTierReason {
    ADULT_MALE,
    FEMALE_ABOVE_CUTOFF,
    /** A boy under 18 at a hospital whose protocol places boys in the low-risk tier. */
    BOY_LOW_RISK_BY_PROTOCOL,
    FEMALE_AT_OR_BELOW_CUTOFF,
    MALE_UNDER_18,
    UNKNOWN_SEX,
    UNKNOWN_AGE,
    NO_ACTIVE_PROTOCOL,
    KNOWN_ANTIBODY
}
