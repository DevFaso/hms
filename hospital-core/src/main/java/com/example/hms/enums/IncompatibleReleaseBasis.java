package com.example.hms.enums;

/**
 * On whose authority a red-cell bag went out despite an incompatible crossmatch (V381, rule set
 * INCOMPATIBLE_RELEASE_AUTHORITY).
 */
public enum IncompatibleReleaseBasis {
    /** The normal path: a physician or the laboratory director, named, who is not the person issuing. */
    NAMED_PHYSICIAN,
    /**
     * The fallback the hospital switched on: no physician could be reached, the issuer said so and released the
     * least incompatible bag under the written protocol. The authority is owed until a physician countersigns.
     */
    AUTHORITY_PENDING
}
