package com.example.hms.enums;

/**
 * Billing-money plan PR 7 (V306), insurer claims plan PR 3 (V457): the life of a payer's share of an invoice.
 *
 * <pre>
 * OPEN --its bordereau is submitted--&gt; CLAIMED --a payment--&gt; PARTIALLY_PAID --closed--&gt; PAID
 * OPEN --reject (reason)--&gt; REJECTED                 (the cover was wrong before any claim)
 * CLAIMED / PARTIALLY_PAID --closed with nothing paid--&gt; REJECTED (all re-billed) or WRITTEN_OFF
 * CLAIMED (its bordereau is cancelled) --&gt; OPEN
 * OPEN --(invoice cancelled, or a credit note replaced the share)--&gt; CANCELLED
 * </pre>
 *
 * A share is closed when {@code amount = paid + rejected + written off}. REJECTED and CANCELLED shares are no longer
 * counted on the invoice. A reversed insurer payment reopens what it had paid.
 */
public enum PayerShareStatus {
    OPEN,
    CLAIMED,
    PARTIALLY_PAID,
    PAID,
    WRITTEN_OFF,
    REJECTED,
    CANCELLED;

    /**
     * Still counted on the invoice and against the annual ceiling, for what the payer still bears of it (the amount
     * less what was re-billed to the patient or written off).
     */
    public boolean isLive() {
        return this == OPEN || this == CLAIMED || this == PARTIALLY_PAID || this == PAID || this == WRITTEN_OFF;
    }

    /** On a submitted bordereau and not closed: its lines take payments, refusals and write-offs. */
    public boolean isAwaitingPayer() {
        return this == CLAIMED || this == PARTIALLY_PAID;
    }
}
