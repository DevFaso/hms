package com.example.hms.enums;

/** On whose authority an O RhD positive red-cell bag went out in an emergency (V380). */
public enum OPositiveDecisionBasis {
    /** The issuer, applying the active protocol of the hospital: the low-risk tier only. */
    PROTOCOL,
    /** A named physician who is not the issuer. */
    PHYSICIAN_ORDER,
    /** A named physician and a biologist together: a patient with a known antibody. */
    PHYSICIAN_AND_BIOLOGIST,
    /**
     * The bag went out with the authority the tier asks for still owed (nobody could be reached, or the
     * biologist was not named). Blood is never held for it: the laboratory director is alerted at once and the
     * countersignature stays due.
     */
    AUTHORITY_PENDING
}
