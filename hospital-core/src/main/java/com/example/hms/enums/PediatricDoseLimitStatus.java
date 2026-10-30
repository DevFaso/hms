package com.example.hms.enums;

/**
 * Round 18 (QA D935): whether a catalogue item's paediatric dose limits are a
 * published default awaiting the hospital pharmacy's approval, or have been
 * approved locally. The prescriber's dose-check card says which.
 */
public enum PediatricDoseLimitStatus {
    /** Loaded from a published reference; the pharmacy lead has not approved it here yet. */
    DEFAULT_PENDING_APPROVAL,
    /** Approved (or set) by this hospital's pharmacy. */
    APPROVED
}
