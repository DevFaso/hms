package com.example.hms.enums;

/**
 * V356: where a hospital stands on one provisional rule set at its current version.
 *
 * <p>Only {@link #SIGNED} and {@link #REVOKED} are stored ({@code hospital.hospital_rule_signoffs.status});
 * {@link #PENDING} is what a rule set with no signed row at its current version reads as, a new version included.
 */
public enum RuleSignoffStatus {
    /** Not signed off at this hospital for the current version: the rule is « provisoire ». */
    PENDING,
    /** Signed off by the hospital's named approver for the current version. */
    SIGNED,
    /** A sign-off of the current version withdrawn; the rule is « provisoire » again. */
    REVOKED
}
