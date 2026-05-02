package com.example.hms.enums;

/**
 * The kind of eligibility transaction. Maps loosely to X12 270/271 (coverage)
 * vs 278 (prior authorization), but the wire format is per-scheme — not X12.
 */
public enum EligibilityCheckType {
    /** Coverage / member-status check (analogue: X12 270/271). */
    COVERAGE,
    /** Prior-authorisation request for a specific service (analogue: X12 278). */
    PRIOR_AUTH
}
