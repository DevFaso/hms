package com.example.hms.enums;

/** How a consent-to-treat was captured (P3 #21). */
public enum TreatmentConsentMethod {
    /** Typed/tapped in the portal or at the front desk. */
    ELECTRONIC,
    /** Spoken consent recorded by staff (e.g. phone or bedside). */
    VERBAL,
    /** A signed paper form exists; the record points at it via notes. */
    PAPER
}
