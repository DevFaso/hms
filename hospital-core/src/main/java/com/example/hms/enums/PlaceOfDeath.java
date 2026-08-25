package com.example.hms.enums;

/** Where the death occurred, as a death certificate records it. */
public enum PlaceOfDeath {
    FACILITY,
    /** Died at home — common where facility delivery is not universal. */
    HOME,
    /**
     * Died on the way. In a setting where the referral journey can take hours,
     * this is a distinct and reportable category, not a rounding error.
     */
    IN_TRANSIT,
    OTHER,
    UNKNOWN
}
