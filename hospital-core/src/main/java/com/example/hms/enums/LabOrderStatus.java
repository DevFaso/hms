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
    CANCELLED,
    /**
     * QA D820: the order, and every result on it, was entered in error (the
     * wrong patient, the wrong test). Set only by {@code POST /lab-orders/{id}/void},
     * with a stated reason, from any other state, a COMPLETED one included.
     * Terminal: nothing moves it again. The rows are kept, never deleted, and
     * its results no longer load anywhere ({@code LabResult}'s restriction).
     * FHIR: ServiceRequest and DiagnosticReport {@code entered-in-error}.
     */
    ENTERED_IN_ERROR
}

