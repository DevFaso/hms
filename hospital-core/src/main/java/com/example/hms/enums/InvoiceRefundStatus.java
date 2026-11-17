package com.example.hms.enums;

/**
 * Billing-money plan PR 8 (V307, QA D1900): the life of a refund of an invoice.
 *
 * <pre>
 * REQUESTED --approve (another HOSPITAL_ADMIN / ACCOUNTANT)--&gt; APPROVED --pay--&gt; PAID
 *     |  \--reject (another HOSPITAL_ADMIN / ACCOUNTANT, reason)--&gt; REJECTED
 *     \----cancel (the requester, reason)--&gt; CANCELLED &lt;--cancel (requester or approver, reason)-- APPROVED
 * </pre>
 *
 * REJECTED, PAID and CANCELLED are final. Only PAID moves money (out of the till).
 */
public enum InvoiceRefundStatus {
    REQUESTED,
    APPROVED,
    REJECTED,
    PAID,
    CANCELLED;

    /** Still holds part of the refund due: asked or approved, not yet paid, refused or withdrawn. */
    public boolean isOpen() {
        return this == REQUESTED || this == APPROVED;
    }
}
