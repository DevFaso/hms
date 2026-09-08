package com.example.hms.enums;

/**
 * The carrier that establishes a live clinical relationship between a patient
 * and the hospital a clinician is acting in (E8 #48). Ordered from strongest
 * to weakest; the resolver reports the strongest one it finds.
 */
public enum TreatmentRelationshipKind {

    /** The patient is admitted here now, or was discharged within the tail. */
    ACTIVE_ADMISSION,

    /** An encounter here is not yet terminal, or completed within the tail. */
    OPEN_ENCOUNTER,

    /** The patient is on the schedule here — today, shortly ahead, or just seen. */
    SCHEDULED_APPOINTMENT,

    /** A standing panel assignment (V149) to a provider at this hospital. */
    PANEL_ASSIGNMENT,

    /** A lab or imaging order placed here that has not reached a terminal status. */
    OPEN_ORDER
}
