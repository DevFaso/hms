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

    private static final Set<EncounterStatus> TERMINAL_STATUSES = EncounterStatus.CLOSED;
    private static final String AUDIT_ENTITY_TYPE = "ENCOUNTER";

    private final EncounterRepository encounterRepository;
    private final PatientTrackerEventPublisher trackerEventPublisher;
    private final AuditEventLogService auditEventLogService;
    /** QA D972: a closed stay and the visit that led to it are handed to billing like a checkout. */
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

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
            publishCompleted(active.get(i), now);
        }
        log.info("Completed {} encounter(s) of admission {} on discharge", active.size(), admissionId);
    }

    /**
     * QA D712: the visit that led to an admission (the emergency visit, or the
     * same-day consultation that decided it) stayed IN_PROGRESS for ever: it
     * carries no admission id, so the discharge (D134) rightly never touched
     * it, and nothing closed it when the patient went to the ward. It ends
     * when the stay begins. Only visits at the stay's hospital, begun on the
     * admission day (any open emergency visit, whatever its start), and never
     * a stay encounter, are closed; each closure is audited.
     */
    @Transactional
    public void completeVisitsThatLedToAdmission(com.example.hms.model.Admission admission) {
        if (admission == null || admission.getId() == null || admission.getPatient() == null
            || admission.getHospital() == null) {
            return;
        }
        LocalDateTime admittedAt = admission.getAdmissionDateTime() != null
            ? admission.getAdmissionDateTime() : LocalDateTime.now();
        // QA D760 (D712 not fixed): the admission time is the one the
        // physician enters, and it is often a little earlier than the moment
        // the admission is recorded (QA backdated it by a minute). The walk-in
        // the reception had opened seconds before then "began after the
        // admission" and was left IN_PROGRESS. A visit is excluded only when
        // it began after the admission was both dated AND recorded.
        LocalDateTime recordedAt = admission.getCreatedAt() != null ? admission.getCreatedAt() : LocalDateTime.now(java.time.ZoneId.systemDefault());
        LocalDateTime cutoff = admittedAt.isAfter(recordedAt) ? admittedAt : recordedAt;
        List<Encounter> open = encounterRepository.findByPatient_IdAndHospital_IdAndStatusNotIn(
                admission.getPatient().getId(), admission.getHospital().getId(), TERMINAL_STATUSES).stream()
            .filter(e -> ledToAdmission(e, admittedAt, cutoff))
            .toList();
        if (open.isEmpty()) {
            return;
        }
        List<String> previousStatuses = open.stream()
                .map(e -> e.getStatus() != null ? e.getStatus().name() : null)
                .toList();
        for (Encounter enc : open) {
            enc.setStatus(EncounterStatus.COMPLETED);
            // Never a checkout before the visit began.
            LocalDateTime began = enc.getEncounterDate();
            enc.setCheckoutTimestamp(began != null && began.isAfter(admittedAt) ? cutoff : admittedAt);
        }
        encounterRepository.saveAll(open);
        for (int i = 0; i < open.size(); i++) {
            trackerEventPublisher.publishStatusTransition(open.get(i),
                    previousStatuses.get(i), EncounterStatus.COMPLETED.name());
            emitAudit(open.get(i), previousStatuses.get(i),
                "Encounter completed on admission " + admission.getId());
            publishCompleted(open.get(i), open.get(i).getCheckoutTimestamp());
        }
    }

    /**
     * QA D972 (round 18): a visit closed here was never billed: only the
     * clinic checkout published the hand-off. The stay's encounter (bed days,
     * the orders of the stay) and the visit that led to the admission are
     * billed like any checked-out visit.
     */
    private void publishCompleted(Encounter encounter, LocalDateTime at) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(com.example.hms.event.EncounterCheckedOutEvent.of(encounter, at));
        }
    }

    static boolean ledToAdmission(Encounter encounter, LocalDateTime admittedAt) {
        return ledToAdmission(encounter, admittedAt, admittedAt);
    }

    /**
     * Whether the visit led to the admission dated {@code admittedAt} and
     * recorded by {@code cutoff} (the later of the two): it began no later
     * than the cutoff, and is an emergency visit, or a same-day outpatient
     * visit (the admission's day, or the day it was recorded).
     */
    static boolean ledToAdmission(Encounter encounter, LocalDateTime admittedAt, LocalDateTime cutoff) {
        if (encounter.getAdmissionId() != null || encounter.getEncounterType() == null) {
            return false;
        }
        LocalDateTime began = encounter.getEncounterDate();
        if (began != null && began.isAfter(cutoff)) {
            return false;
        }
        return switch (encounter.getEncounterType()) {
            case EMERGENCY -> true;
            case CONSULTATION, OUTPATIENT, FOLLOW_UP ->
                began != null && (began.toLocalDate().equals(admittedAt.toLocalDate())
                    || began.toLocalDate().equals(cutoff.toLocalDate()));
            default -> false;
        };
    }

    private void emitAudit(Encounter encounter, String previousStatus, UUID admissionId) {
        emitAudit(encounter, previousStatus, "Encounter completed on discharge of admission " + admissionId);
    }

    /** Ids only: no PHI in the description. Audit never rolls back the admission or discharge. */
    private void emitAudit(Encounter encounter, String previousStatus, String what) {
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.ENCOUNTER_UPDATE)
                    .status(AuditStatus.SUCCESS)
                    .entityType(AUDIT_ENTITY_TYPE)
                    .resourceId(encounter.getId() == null ? null : encounter.getId().toString())
                    .eventDescription(what + " (was " + previousStatus + ")")
                    .build());
        } catch (RuntimeException ex) {
            log.warn("audit emission failed for discharge completion of encounter {}: {}",
                    encounter.getId(), ex.toString());
        }
    }
}
