package com.example.hms.enums;

/**
 * Lifecycle of a release-of-information request (Tier 2 item 39b).
 * PENDING is the worklist; the three closed states are terminal — a
 * decided request is history, never edited back.
 */
public enum RoiRequestStatus {
    PENDING,
    FULFILLED,
    DENIED,
    CANCELLED
}
