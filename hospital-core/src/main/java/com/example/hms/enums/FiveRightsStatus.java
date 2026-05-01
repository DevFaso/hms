package com.example.hms.enums;

/**
 * Outcome of the bedside five-rights barcode-scan check on a medication
 * administration record. Recorded on the {@code MedicationAdministrationRecord}
 * once the nurse completes (or overrides) verification.
 */
public enum FiveRightsStatus {
    /** Scan workflow has not been performed for this dose. */
    NOT_VERIFIED,
    /** All five rights matched — patient, drug, dose, route and time. */
    VERIFIED,
    /** One or more rights failed but the nurse proceeded with a recorded reason. */
    OVERRIDDEN
}
