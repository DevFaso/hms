package com.example.hms.enums;

/**
 * Insurer claims plan PR 4 (V458): a write-off is asked for, then approved or refused by another person (four eyes),
 * or cancelled while it still waits.
 */
public enum ClaimWriteOffStatus {
    REQUESTED,
    APPROVED,
    REFUSED,
    CANCELLED
}
