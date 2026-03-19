package com.example.hms.enums;

/**
 * Status of a medication administration event recorded by nursing staff.
 */
public enum MedicationAdministrationStatus {
    /** Medication is due but not yet acted on. */
    PENDING,
    /** Medication was successfully administered to the patient. */
    GIVEN,
    /** Medication was temporarily held (requires reason). */
    HELD,
    /** Patient refused medication (requires reason). */
    REFUSED,
    /** Medication window was missed without administration. */
    MISSED
}
