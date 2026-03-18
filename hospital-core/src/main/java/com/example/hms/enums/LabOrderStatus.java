package com.example.hms.enums;

public enum LabOrderStatus {
    /** Order has been placed by the ordering provider. */
    ORDERED,
    /** Order acknowledged, awaiting specimen collection. */
    PENDING,
    /** Specimen collected from patient, en-route to lab. */
    COLLECTED,
    /** Specimen received at the laboratory. */
    RECEIVED,
    /** Analysis in progress. */
    IN_PROGRESS,
    /** Test results have been entered. */
    RESULTED,
    /** Results reviewed and verified by authorised personnel. */
    VERIFIED,
    /** Results released / order closed. */
    COMPLETED,
    /** Order cancelled before completion. */
    CANCELLED
}

