package com.example.hms.enums;

/**
 * Outcome of a real-time eligibility / prior-auth check.
 *
 * <ul>
 *   <li>{@link #ELIGIBLE}    — coverage active, service is covered</li>
 *   <li>{@link #NOT_ELIGIBLE} — coverage inactive or service not covered</li>
 *   <li>{@link #PENDING}     — payer accepted the request and will respond async</li>
 *   <li>{@link #UNKNOWN}     — payer did not return a definitive answer (timeout, soft-fail)</li>
 *   <li>{@link #ERROR}       — call failed (auth, schema, network) — treat as a hard failure</li>
 * </ul>
 */
public enum EligibilityStatus {
    ELIGIBLE,
    NOT_ELIGIBLE,
    PENDING,
    UNKNOWN,
    ERROR
}
