package com.example.hms.event;

import com.example.hms.model.Encounter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Published whenever a visit is completed, so billing can turn it into a
 * draft invoice without the clinic code knowing anything about billing (QA
 * D972). Every path that completes a visit publishes it through
 * {@link #of(Encounter, LocalDateTime)}: the checkout, a PUT that completes
 * the visit, the discharge that closes a stay's encounter, the admission that
 * ends the emergency visit that led to it, and a recorded death
 * ({@code VisitCompletionBillingGuardTest}).
 *
 * <p>Ids and scalars only, no JPA entities: a consumer listens with
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} and re-loads what
 * it prices in its own transaction, so a failed charge can never roll the
 * checkout back. QA D972 (round 18): the event no longer carries hand-picked
 * order id lists (imaging was missing from them); billing reads every
 * billable item of the visit by its id, through its registry of billable
 * order types.
 *
 * @param encounterId     the completed visit
 * @param encounterCode   its human code (ENC-…), for the invoice note
 * @param patientId       the patient billed
 * @param hospitalId      the hospital whose price list applies
 * @param departmentId    the department of the visit, or null
 * @param providerStaffId the treating clinician, or null
 * @param encounterType   OUTPATIENT, EMERGENCY, INPATIENT, …
 * @param checkedOutAt    when the visit was closed
 */
public record EncounterCheckedOutEvent(
    UUID encounterId,
    String encounterCode,
    UUID patientId,
    UUID hospitalId,
    UUID departmentId,
    UUID providerStaffId,
    String encounterType,
    LocalDateTime checkedOutAt
) {

    /** The event for a visit completed at {@code checkedOutAt} (its checkout time when null). */
    public static EncounterCheckedOutEvent of(Encounter encounter, LocalDateTime checkedOutAt) {
        LocalDateTime at = checkedOutAt != null ? checkedOutAt : encounter.getCheckoutTimestamp();
        return new EncounterCheckedOutEvent(
            encounter.getId(),
            encounter.getCode(),
            encounter.getPatient() != null ? encounter.getPatient().getId() : null,
            encounter.getHospital() != null ? encounter.getHospital().getId() : null,
            encounter.getDepartment() != null ? encounter.getDepartment().getId() : null,
            encounter.getStaff() != null ? encounter.getStaff().getId() : null,
            encounter.getEncounterType() != null ? encounter.getEncounterType().name() : null,
            at);
    }
}
