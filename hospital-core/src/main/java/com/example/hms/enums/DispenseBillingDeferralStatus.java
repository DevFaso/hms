package com.example.hms.enums;

/** Policy rule 6 (V345): REQUESTED, then APPROVED or REJECTED by another person, EXECUTED at the hand-over. */
public enum DispenseBillingDeferralStatus {
    REQUESTED,
    APPROVED,
    REJECTED,
    /** The fill left the pharmacy on this deferral. */
    EXECUTED,
    /** Withdrawn before any hand-over, with a reason. */
    CANCELLED
}
