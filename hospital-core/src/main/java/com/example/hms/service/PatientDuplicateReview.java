package com.example.hms.service;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.PatientRequestDTO;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class PatientDuplicateReview {
    private final ReceptionService reception;
    private final RequiredAuditEventLogPort audit;
    private final RoleValidator roles;

    public record Decision(UUID hospitalId, UUID actorUserId, List<UUID> candidates, String reason) {
        public Decision { candidates = List.copyOf(candidates); }
    }

    /** Recompute in the target hospital; never trust candidate IDs supplied by a browser. */
    public Decision prepare(PatientRequestDTO request, UUID hospitalId) {
        var candidates = reception.getDuplicateCandidates(
            java.util.stream.Stream.of(request.getFirstName(), request.getMiddleName(), request.getLastName()).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.joining(" ")),
            request.getDateOfBirth() != null ? request.getDateOfBirth().toString() : null,
            request.getPhoneNumberPrimary(), hospitalId);
        if (candidates.isEmpty()) return null;
        String reason = request.getDuplicateReviewReason();
        if (reason == null || reason.isBlank() || reason.strip().length() > 2000) {
            throw new FieldBusinessException("duplicateReviewReason", "patient.duplicateReview.reasonRequired");
        }
        UUID actor = roles.getCurrentUserId();
        if (actor == null) throw new org.springframework.security.access.AccessDeniedException("patient.identity.reviewActorRequired");
        return new Decision(hospitalId, actor, candidates.stream().map(c -> c.getPatientId()).distinct().sorted().toList(), reason.strip());
    }

    /** Same registration transaction: if this insert fails, the patient creation rolls back. */
    public void record(Decision decision, Patient patient) {
        if (decision == null) return;
        var recorded = audit.logRequiredEvent(AuditEventRequestDTO.builder()
            .hospitalId(decision.hospitalId()).userId(decision.actorUserId())
            .eventType(AuditEventType.PATIENT_CREATE).status(AuditStatus.SUCCESS)
            .eventDescription("Possible duplicate kept as a separate patient")
            .entityType("PATIENT").resourceId(patient.getId().toString())
            .details(Map.of("decision", "KEEP_SEPARATE", "candidatePatientIds", decision.candidates(), "reason", decision.reason()))
            .build());
        if (recorded == null || recorded.getId() == null) {
            throw new IllegalStateException("patient.identity.auditMissing");
        }
    }
}
