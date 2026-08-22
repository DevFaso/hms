package com.example.hms.enums;

/** Where a consent-to-treat record was created (P3 #21). */
public enum TreatmentConsentSource {
    /** Front-desk check-in dialog. */
    CHECK_IN,
    /** Patient-portal pre-check-in (the consentAcknowledged checkbox). */
    PRE_CHECK_IN,
    /** Recorded directly via the consent endpoints. */
    MANUAL
}
