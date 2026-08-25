package com.example.hms.enums;

/**
 * Outcome of the dispense-time verification loop, recorded on the
 * {@code Dispense} row.
 *
 * <p>Mirrors {@link FiveRightsStatus} deliberately: dispensing and
 * administration are two steps of one medication chain, and a pharmacist and
 * a nurse looking at the same patient's record should not have to learn two
 * vocabularies for the same idea.
 */
public enum DispenseVerificationStatus {

    /**
     * No scan was performed. This is the paper-fallback path and a
     * legitimate outcome, not a failure — most sites in this deployment have
     * no scanner. The server-side expiry and drug-match checks still ran.
     */
    NOT_VERIFIED,

    /** Every scan value supplied matched. */
    VERIFIED,

    /**
     * A check failed and the pharmacist proceeded with a recorded reason.
     * Reachable only for the checks {@link DispenseCheck} allows to be
     * overridden — an expired lot is refused outright and no row is written.
     */
    OVERRIDDEN
}
