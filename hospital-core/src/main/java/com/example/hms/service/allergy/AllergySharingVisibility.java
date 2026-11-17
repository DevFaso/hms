package com.example.hms.service.allergy;

import com.example.hms.model.PatientAllergy;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.service.recordaccess.RecordAccessDecision;
import com.example.hms.service.recordaccess.RecordAccessPolicy;

import java.util.List;
import java.util.UUID;

/**
 * QA D504 — which allergy rows a reader at a hospital may see, asked the same
 * way on every read path (allergy list, doctor record, timeline, storyboard,
 * snapshot, patient summary column, nurse dashboard).
 *
 * <p>Allergies follow the patient (E9 #56), except that a patient who opted out
 * of record sharing keeps other hospitals' rows there (QA D110). Round 7 (D410)
 * made a live break-the-glass session override the opt-out in
 * {@link RecordAccessPolicy}, but the allergy reads filtered on the opt-out
 * alone and never asked the policy: break-glass said PERMITTED and the chart
 * said "no allergies". Every path now asks the policy, so the opt-out and its
 * emergency override are one decision; and when rows ARE withheld,
 * {@link #foreignRowsWithheld} lets the response say so instead of returning a
 * list that looks complete.
 */
public final class AllergySharingVisibility {

    private AllergySharingVisibility() {
    }

    /**
     * True when the patient opted out of sharing and the actor's access here
     * does not override it, i.e. other hospitals' rows are being held back.
     * Says nothing about whether such rows exist.
     */
    public static boolean foreignRowsWithheld(PatientAllergyRepository repository, RecordAccessPolicy policy,
                                              UUID actorUserId, UUID patientId, UUID hospitalId) {
        if (patientId == null || hospitalId == null || !repository.hasActiveSharingOptOut(patientId)) {
            return false;
        }
        return !optOutOverridden(policy, actorUserId, patientId, hospitalId);
    }

    /** Every row the reader may see: all rows unless {@link #foreignRowsWithheld}. */
    public static List<PatientAllergy> rowsVisibleAt(PatientAllergyRepository repository, RecordAccessPolicy policy,
                                                     UUID actorUserId, UUID patientId, UUID hospitalId) {
        if (hospitalId == null) {
            return repository.findByPatient_Id(patientId);
        }
        if (repository.hasActiveSharingOptOut(patientId)
                && optOutOverridden(policy, actorUserId, patientId, hospitalId)) {
            return repository.findByPatient_Id(patientId);
        }
        return repository.findVisibleAt(patientId, hospitalId);
    }

    /** Under an opt-out the policy permits only a live break-the-glass session (D410). */
    private static boolean optOutOverridden(RecordAccessPolicy policy, UUID actorUserId, UUID patientId, UUID hospitalId) {
        if (policy == null || actorUserId == null) {
            return false;
        }
        RecordAccessDecision decision = policy.decide(actorUserId, patientId, hospitalId);
        return decision != null && decision.permitted();
    }
}
