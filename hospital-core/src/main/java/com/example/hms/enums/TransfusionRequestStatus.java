package com.example.hms.enums;

/** Lifecycle of a clinician's request for blood. */
public enum TransfusionRequestStatus {
    REQUESTED,
    CROSSMATCHED,
    ISSUED,
    COMPLETED,
    CANCELLED
}
