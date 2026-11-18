package com.example.hms.enums;

/**
 * Billing-money plan PR 6: where a payer plan or one of its rules stands on the hospital's business day. DERIVED from
 * its dates ({@code PayerCoverageRules.statusOf}), never stored.
 */
public enum PayerCoverageStatus {
    /** Starts after today. */
    SCHEDULED,
    /** Applies today. */
    IN_FORCE,
    /** Applied, and stopped (ended, or a newer rule took over). */
    ENDED,
    /** Withdrawn or replaced before it ever applied: kept in the history. */
    CANCELLED
}
