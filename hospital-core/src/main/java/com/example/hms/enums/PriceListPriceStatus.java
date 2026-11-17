package com.example.hms.enums;

/**
 * Billing-money plan PR 2: where a price version stands on the hospital's business day. DERIVED from its dates
 * ({@code PriceVersionRules.statusOf}), never stored.
 */
public enum PriceListPriceStatus {
    /** Starts after today. */
    SCHEDULED,
    /** In force today. */
    IN_FORCE,
    /** Was in force, and a newer version took over. */
    ENDED,
    /** Replaced before it ever started: never in force, kept in the history. */
    REPLACED
}
