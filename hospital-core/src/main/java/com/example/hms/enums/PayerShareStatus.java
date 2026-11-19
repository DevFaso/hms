package com.example.hms.enums;

/**
 * Billing-money plan PR 7 (V306): the life of a payer's share of an invoice.
 *
 * <pre>
 * OPEN --claim--&gt; CLAIMED --paid--&gt; PAID
 *   |  \--paid--&gt; PAID          |
 *   \--reject (reason)--&gt; REJECTED &lt;--reject (reason)--/
 * OPEN --(invoice cancelled, or a credit note replaced the share)--&gt; CANCELLED
 * </pre>
 *
 * REJECTED, PAID and CANCELLED are final. A REJECTED share moves back to the patient; the row keeps its amount.
 */
public enum PayerShareStatus {
    OPEN,
    CLAIMED,
    PAID,
    REJECTED,
    CANCELLED;

    /** Still counted as owed by the payer: on the invoice and against the annual ceiling. */
    public boolean isLive() {
        return this == OPEN || this == CLAIMED || this == PAID;
    }
}
