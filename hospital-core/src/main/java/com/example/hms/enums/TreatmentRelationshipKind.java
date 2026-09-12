package com.example.hms.enums;

/**
 * The carrier that establishes a live clinical relationship between a patient
 * and the hospital a clinician is acting in (E8 #48). Ordered from strongest
 * to weakest; the resolver reports the strongest one it finds.
 */
public enum TreatmentRelationshipKind {

    /**
     * The patient is registered at this hospital — reception linked them
     * (E9 #58, decision D1). The automatic relationship: a deliberate act by a
     * person at a desk, already required for every write, made for every
     * patient who walks in. Strongest, and it does not lapse.
     */
    REGISTRATION,

    /** The patient is admitted here now, or was discharged within the tail. */
    ACTIVE_ADMISSION,

    /** An encounter here is not yet terminal, or completed within the tail. */
    OPEN_ENCOUNTER,

    /** The patient is on the schedule here — today, shortly ahead, or just seen. */
    SCHEDULED_APPOINTMENT,

    /** A standing panel assignment (V149) to a provider at this hospital. */
    PANEL_ASSIGNMENT,

    /** A lab or imaging order placed here that has not reached a terminal status. */
    OPEN_ORDER,

    /**
     * No registration and no carrier, but the actor declared a break-the-glass
     * session for this patient at this hospital (E9 #62, decision D2 — Tier B).
     * Time-boxed, audited on declaration, and every disclosure row it enables
     * carries the session id. Weakest, and it lapses with the session.
     */
    BREAK_GLASS
}
