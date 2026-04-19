package com.example.hms.enums;

public enum PrescriptionStatus {
    DRAFT,
    PENDING_SIGNATURE,
    SIGNED,
    TRANSMITTED,
    TRANSMISSION_FAILED,
    CANCELLED,
    DISCONTINUED,
    /** Prescription flagged by pharmacy and awaiting physician clarification. */
    PENDING_CLARIFICATION,
    /** Medication fully dispensed at an internal pharmacy. */
    DISPENSED,
    /** Only part of the prescribed quantity was dispensed. */
    PARTIALLY_FILLED,
    /** Medication not in stock; awaiting restock before dispensing. */
    PENDING_STOCK,
    /** Prescription requires fill at an external / partner pharmacy. */
    REQUIRES_EXTERNAL_FILL,
    /** Prescription forwarded to a partner pharmacy. */
    SENT_TO_PARTNER,
    /** Partner pharmacy acknowledged the prescription. */
    PARTNER_ACCEPTED,
    /** Partner pharmacy rejected the prescription. */
    PARTNER_REJECTED,
    /** Partner pharmacy has dispensed the medication. */
    PARTNER_DISPENSED,
    /** Prescription printed for the patient to take to an external pharmacy. */
    PRINTED_FOR_PATIENT
}
