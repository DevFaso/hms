package com.example.hms.payload.dto;

import java.util.List;
import java.util.UUID;

/**
 * QA r65 D2731 (and the E3 enhancement of the platform report): what a new
 * hospital still needs before it can work, and above all before a patient
 * can book online.
 *
 * @param hospitalId     the hospital the answer is about
 * @param patientBookable true when at least one department has both a
 *                       bookable clinician attached and a published slot
 *                       still to come: exactly what the patient booking
 *                       lookups need to offer something
 * @param items          the checklist, in the order a hospital is set up
 */
public record HospitalOnboardingReadinessDTO(UUID hospitalId, boolean patientBookable, List<Item> items) {

    /** The steps of the checklist. The portal owns their wording and where each one is done. */
    public enum Step {
        /** At least one department. */
        DEPARTMENTS,
        /** At least one activated staff account. */
        STAFF,
        /** At least one department with a doctor, nurse or midwife attached to it. */
        PROVIDERS_IN_DEPARTMENT,
        /** At least one active session template (a clinician's weekly rota). */
        SESSION_TEMPLATES,
        /** At least one published slot still to come, in a department a patient can book. */
        OPEN_SLOTS,
        /** At least one active bed (inpatient care). */
        BEDS,
        /** At least one active price-list item (billing). */
        PRICE_LIST,
        /** At least one active payer (insured patients). */
        PAYERS
    }

    /**
     * @param step               which step
     * @param done               whether the hospital has it
     * @param count              how many it has (departments, staff, slots…)
     * @param requiredForBooking true for the steps online booking cannot work without
     */
    public record Item(Step step, boolean done, long count, boolean requiredForBooking) {
    }
}
