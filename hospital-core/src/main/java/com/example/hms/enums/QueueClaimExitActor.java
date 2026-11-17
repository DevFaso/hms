package com.example.hms.enums;

/**
 * G13: who performed a write that ends a work-queue claim. Decided by the
 * caller, which knows its own gate, never guessed by the claim service.
 * <ul>
 *   <li>{@link #QUEUE_ROLE}: PHARMACIST, PHARMACY_VERIFIER, HOSPITAL_ADMIN or
 *       SUPER_ADMIN; acting over a colleague's claim is a take-over;</li>
 *   <li>{@link #OTHER}: anyone else (a prescriber's edit, a nurse's SMS
 *       dispatch); the claim ends as a plain release.</li>
 * </ul>
 */
public enum QueueClaimExitActor {
    QUEUE_ROLE,
    OTHER
}
