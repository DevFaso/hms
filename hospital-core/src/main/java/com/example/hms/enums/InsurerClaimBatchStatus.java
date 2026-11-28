package com.example.hms.enums;

/**
 * Insurer claims plan PR 2 (V455): the life of a bordereau, the claim sheet sent to one payer for one period.
 *
 * <pre>
 * DRAFT --submit--&gt; SUBMITTED --first settlement--&gt; PARTIALLY_SETTLED --every line closed--&gt; SETTLED
 * SUBMITTED (nothing settled) --cancel, with a reason--&gt; CANCELLED
 * </pre>
 *
 * A draft is deleted, never cancelled. A reversed payment can take a SETTLED bordereau back to PARTIALLY_SETTLED.
 */
public enum InsurerClaimBatchStatus {
    DRAFT,
    SUBMITTED,
    PARTIALLY_SETTLED,
    SETTLED,
    CANCELLED;

    /** Sent to the payer and not cancelled: its lines take payments, refusals and write-offs. */
    public boolean isSent() {
        return this == SUBMITTED || this == PARTIALLY_SETTLED || this == SETTLED;
    }
}
