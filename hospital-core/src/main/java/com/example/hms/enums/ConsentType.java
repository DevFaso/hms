package com.example.hms.enums;

/**
 * Categorises the purpose of a cross-organisation patient consent.
 */
public enum ConsentType {

    /** Sharing for direct clinical treatment. */
    TREATMENT,

    /** Sharing for clinical or academic research. */
    RESEARCH,

    /** Sharing for billing and insurance processing. */
    BILLING,

    /** Sharing under emergency/break-glass scenario. */
    EMERGENCY,

    /** Sharing to support a formal referral. */
    REFERRAL,

    /** Unrestricted sharing for all purposes. */
    ALL_PURPOSES
}
