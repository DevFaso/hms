package com.example.hms.service.recordaccess;

import com.example.hms.enums.RecordAccessDenialReason;
import com.example.hms.enums.RecordAccessPosture;

import java.util.UUID;

/**
 * The answer to "may this actor, acting at this hospital, read this patient's
 * chart from other hospitals?" — with the reason, so an audit row (E8 #53) and
 * a portal message can both say which gate decided it.
 *
 * @param permitted     true only when {@code reason == PERMITTED}
 * @param reason        the first gate that closed, or PERMITTED
 * @param relationship  the carrier found, when one was looked for and found
 * @param posture       the acting hospital's posture, when it was resolved
 */
public record RecordAccessDecision(
    UUID patientId,
    UUID actingHospitalId,
    UUID actorUserId,
    boolean permitted,
    RecordAccessDenialReason reason,
    TreatmentRelationship relationship,
    RecordAccessPosture posture
) {
    public static RecordAccessDecision refused(UUID patientId, UUID hospitalId, UUID actorUserId,
                                               RecordAccessDenialReason reason,
                                               RecordAccessPosture posture) {
        return new RecordAccessDecision(patientId, hospitalId, actorUserId, false, reason, null, posture);
    }

    public static RecordAccessDecision permitted(UUID patientId, UUID hospitalId, UUID actorUserId,
                                                 TreatmentRelationship relationship,
                                                 RecordAccessPosture posture) {
        return new RecordAccessDecision(patientId, hospitalId, actorUserId, true,
            RecordAccessDenialReason.PERMITTED, relationship, posture);
    }
}
