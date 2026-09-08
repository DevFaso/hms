package com.example.hms.payload.dto.recordaccess;

import com.example.hms.enums.RecordAccessDenialReason;
import com.example.hms.enums.RecordAccessPosture;
import com.example.hms.enums.TreatmentRelationshipKind;
import com.example.hms.service.recordaccess.RecordAccessDecision;
import com.example.hms.service.recordaccess.TreatmentRelationship;

import java.time.LocalDateTime;
import java.util.UUID;

/** Wire shape of {@link RecordAccessDecision}; ids and enums only, no PHI. */
public record RecordAccessDecisionDTO(
    UUID patientId,
    UUID actingHospitalId,
    boolean permitted,
    RecordAccessDenialReason reason,
    RecordAccessPosture hospitalPosture,
    TreatmentRelationshipKind relationshipKind,
    UUID carrierId,
    LocalDateTime establishedAt,
    LocalDateTime expiresAt,
    Boolean actorDirectlyAttached
) {
    public static RecordAccessDecisionDTO from(RecordAccessDecision d) {
        TreatmentRelationship r = d.relationship();
        return new RecordAccessDecisionDTO(
            d.patientId(), d.actingHospitalId(), d.permitted(), d.reason(), d.posture(),
            r == null ? null : r.kind(),
            r == null ? null : r.carrierId(),
            r == null ? null : r.establishedAt(),
            r == null ? null : r.expiresAt(),
            r == null ? null : r.actorDirectlyAttached());
    }
}
