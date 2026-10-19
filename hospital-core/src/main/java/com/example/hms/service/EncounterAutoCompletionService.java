package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.model.Encounter;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.EncounterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Completes an admission's own open encounters when the admission is
 * discharged. Shared by both discharge paths — the direct admission discharge
 * ({@code POST /admissions/{id}/discharge}) and the nurse-request /
 * doctor-approve flow ({@code POST /discharge-approvals/{id}/approve}).
 *
 * <p>QA D134: this used to complete EVERY open encounter the patient had at
 * the hospital, so discharging a stay also closed an unrelated outpatient
 * consultation in progress, silently, and generated an after-visit summary
 * for it. Only the encounters that carry the admission's id
 * ({@code clinical.encounters.admission_id}, V193) are closed now, and each
 * closure is audited.
 *
 * <p>Publishes a tracker status-transition event per encounter so
 * WebSocket-connected tracker boards update immediately.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EncounterAutoCompletionService {

    private static final Set<EncounterStatus> TERMINAL_STATUSES = EnumSet.of(
            EncounterStatus.COMPLETED, EncounterStatus.CANCELLED);
    private static final String AUDIT_ENTITY_TYPE = "ENCOUNTER";

    private final EncounterRepository encounterRepository;
    private final PatientTrackerEventPublisher trackerEventPublisher;
    private final AuditEventLogService auditEventLogService;

    @Transactional
    public void completeAdmissionEncounters(UUID admissionId) {
        if (admissionId == null) {
            return;
        }
        List<Encounter> active = encounterRepository.findByAdmissionIdAndStatusNotIn(
                admissionId, TERMINAL_STATUSES);
        if (active.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<String> previousStatuses = active.stream()
                .map(e -> e.getStatus() != null ? e.getStatus().name() : null)
                .toList();
        for (Encounter enc : active) {
            enc.setStatus(EncounterStatus.COMPLETED);
            enc.setCheckoutTimestamp(now);
        }
        encounterRepository.saveAll(active);
        for (int i = 0; i < active.size(); i++) {
            trackerEventPublisher.publishStatusTransition(active.get(i),
                    previousStatuses.get(i), EncounterStatus.COMPLETED.name());
            emitAudit(active.get(i), previousStatuses.get(i), admissionId);
        }
        log.info("Completed {} encounter(s) of admission {} on discharge", active.size(), admissionId);
    }

    /** Ids only: no PHI in the description. Audit never rolls back the discharge. */
    private void emitAudit(Encounter encounter, String previousStatus, UUID admissionId) {
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.ENCOUNTER_UPDATE)
                    .status(AuditStatus.SUCCESS)
                    .entityType(AUDIT_ENTITY_TYPE)
                    .resourceId(encounter.getId() == null ? null : encounter.getId().toString())
                    .eventDescription("Encounter completed on discharge of admission " + admissionId
                            + " (was " + previousStatus + ")")
                    .build());
        } catch (RuntimeException ex) {
            log.warn("audit emission failed for discharge completion of encounter {}: {}",
                    encounter.getId(), ex.toString());
        }
    }
}
