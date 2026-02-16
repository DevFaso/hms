package com.example.hms.enums;

/**
 * Identifies the type of clinical artifact that has been explicitly linked to an encounter note for auditing
 * and quick recall in the UI.
 */
public enum EncounterNoteLinkType {
    LAB_ORDER,
    PRESCRIPTION,
    REFERRAL,
    RADIOLOGY_ORDER,
    CARE_PLAN,
    OTHER
}
