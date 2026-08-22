package com.example.hms.enums;

/**
 * Lifecycle of a consent-to-treat record (P3 #21). Revoke-never-delete,
 * following the AdvanceDirectiveStatus idiom.
 */
public enum TreatmentConsentStatus {
    ACTIVE,
    REVOKED,
    EXPIRED
}
