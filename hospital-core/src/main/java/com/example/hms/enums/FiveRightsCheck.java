package com.example.hms.enums;

/**
 * Individual safety check inside the bedside five-rights verification loop.
 * Each value corresponds to one column in the verification result returned by
 * the eMAR verify endpoint and persisted in {@code five_rights_overrides}
 * when an override is recorded.
 */
public enum FiveRightsCheck {
    /** Wristband barcode resolves to the prescription's patient. */
    PATIENT,
    /** Drug barcode resolves to the prescribed medication (code or name). */
    DRUG,
    /** Scanned dose matches the prescribed dose. */
    DOSE,
    /** Scanned route matches the prescribed route. */
    ROUTE,
    /** Administration falls inside the configured window around scheduled time. */
    TIME
}
