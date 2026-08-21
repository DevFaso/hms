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
    PRINTED_FOR_PATIENT;

    /**
     * Whether this prescription is still a live authorization a refill can be
     * released against.
     *
     * <p>Note what is deliberately refillable: DISPENSED and PARTIALLY_FILLED.
     * A patient asks for a refill precisely because they have already collected
     * the medication, so treating a dispensed prescription as spent would refuse
     * every refill that matters. Only a prescription that was never signed, or
     * has been withdrawn, is genuinely un-refillable.
     *
     * <p>Single source of truth for the patient request gate, the approval gate
     * and the portal UI — they disagreed before, and the UI's version hid the
     * refill button on exactly the prescriptions patients needed it for.
     */
    public boolean isRefillable() {
        return switch (this) {
            case DRAFT, PENDING_SIGNATURE, CANCELLED, DISCONTINUED -> false;
            default -> true;
        };
    }
}
