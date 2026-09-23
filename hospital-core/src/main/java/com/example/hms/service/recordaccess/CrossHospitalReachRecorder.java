package com.example.hms.service.recordaccess;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.service.AuditEventLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import com.example.hms.model.Hospital;
import java.util.Optional;

/**
 * E8 #53 / E9 — accounts for the REACH of a cross-hospital read, not just the
 * access: one {@code RECORD_SHARE} row per foreign source hospital naming the
 * actor, the hospital they acted in, the hospital whose rows were surfaced and
 * how many. The patient's disclosure report reads from these rows.
 *
 * <p>Extracted from {@code PatientServiceImpl} in E9 #59 so every surface that
 * now returns foreign rows — diagnoses, nursing notes, chart updates, advance
 * directives, the doctor record, and since #59b lab and imaging orders, lab
 * results, procedure orders, consultations and referrals — records reach the
 * same way instead of each
 * carrying its own copy. A failure to audit is logged and never fails the read:
 * the row was already authorised by the policy; the ledger is the second line.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CrossHospitalReachRecorder {

    static final String ENTITY_TYPE_PATIENT = "PATIENT";
    static final String DETAIL_ACTING_HOSPITAL_ID = "actingHospitalId";
    static final String DETAIL_SOURCE_HOSPITAL_ID = "sourceHospitalId";
    static final String DETAIL_ROWS_SURFACED = "rowsSurfaced";
    /** E9 #62 — present when the read ran under a live break-the-glass session. */
    static final String DETAIL_BREAK_GLASS_SESSION_ID = "breakGlassSessionId";

    private final AuditEventLogService auditEventLogService;
    private final BreakGlassGate breakGlassGate;
    private final com.example.hms.repository.AuditEventLogRepository auditEventLogRepository;

    /** Reads {@code sourceHospitalId} back out of a recorded row's details JSON. */
    private static final java.util.regex.Pattern SOURCE_HOSPITAL_IN_DETAILS =
        java.util.regex.Pattern.compile("\"" + DETAIL_SOURCE_HOSPITAL_ID + "\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * Count the source hospitals in {@code sourceHospitalIds} that are not
     * {@code actingHospitalId}, keyed by source hospital id — one entry per row
     * surfaced, mapped by the caller in the open ({@link #hospitalIdOf} for an
     * entity, {@code getHospitalId} for a DTO). A null id is a row with no
     * hospital, which is not foreign. The list is never null — a null guard
     * here taught Sonar that every caller's list might be null (S2259).
     */
    public static Map<String, Long> reachOf(Collection<UUID> sourceHospitalIds, UUID actingHospitalId) {
        Map<String, Long> perSource = new HashMap<>();
        for (UUID source : sourceHospitalIds) {
            if (source != null && !source.equals(actingHospitalId)) {
                perSource.merge(source.toString(), 1L, Long::sum);
            }
        }
        return perSource;
    }

    /** The id of an entity's {@code hospital} association, or {@code null} when it has none. */
    public static UUID hospitalIdOf(Hospital hospital) {
        return hospital == null ? null : hospital.getId();
    }

    /** Merge {@code more} into {@code into}, summing counts per source hospital. */
    public static Map<String, Long> merge(Map<String, Long> into, Map<String, Long> more) {
        more.forEach((k, v) -> into.merge(k, v, Long::sum));
        return into;
    }

    /**
     * Batched sibling of {@link #recordReach} for a list read: the disclosures
     * of a whole page in one dedupe query and one transaction, where the
     * per-patient loop cost a break-glass query and a committed transaction
     * each — a few hundred patients on a worklist meant a few hundred of both,
     * on every refresh.
     *
     * <p>A disclosure the same actor already has for that patient and source
     * hospital <strong>today</strong> is not written again. The accounting
     * defines no repeat of its own — {@code DisclosureAccountingServiceImpl}
     * counts and lists every row over whatever window the reader asks for — so
     * without a bound here a clinician refreshing a worklist would fill the
     * patient's own disclosure report with the same line. The calendar day is
     * the narrowest bound that still reads as one disclosure episode to the
     * patient; the first row of the day carries its {@code rowsSurfaced} and
     * later identical reads add nothing.
     *
     * @param perPatient patient id -> (source hospital id -> rows surfaced)
     */
    public void recordBatchedReach(Map<UUID, Map<String, Long>> perPatient, UUID actingHospitalId,
                                   UUID requesterUserId, UUID assignmentId, String description) {
        if (perPatient == null || perPatient.isEmpty()) {
            return;
        }
        Set<String> alreadyToday = disclosuresAlreadyRecordedToday(requesterUserId, perPatient.keySet());
        Map<UUID, Optional<UUID>> breakGlassByPatient = new HashMap<>();
        List<AuditEventRequestDTO> pending = new ArrayList<>();

        for (Map.Entry<UUID, Map<String, Long>> patient : perPatient.entrySet()) {
            UUID patientId = patient.getKey();
            if (patientId == null) {
                continue;
            }
            for (Map.Entry<String, Long> reach : patient.getValue().entrySet()) {
                if (alreadyToday.contains(dedupeKey(patientId, reach.getKey()))) {
                    continue;
                }
                Optional<UUID> breakGlassSessionId = breakGlassByPatient.computeIfAbsent(patientId,
                    id -> breakGlassGate.liveSessionId(requesterUserId, id, actingHospitalId));
                Map<String, Object> details = new HashMap<>();
                details.put(DETAIL_ACTING_HOSPITAL_ID, String.valueOf(actingHospitalId));
                details.put(DETAIL_SOURCE_HOSPITAL_ID, reach.getKey());
                details.put(DETAIL_ROWS_SURFACED, reach.getValue());
                breakGlassSessionId.ifPresent(id -> details.put(DETAIL_BREAK_GLASS_SESSION_ID, id.toString()));
                pending.add(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.RECORD_SHARE)
                    .status(AuditStatus.SUCCESS)
                    .userId(requesterUserId)
                    .assignmentId(assignmentId)
                    .patientId(patientId)
                    .entityType(ENTITY_TYPE_PATIENT)
                    .resourceId(patientId.toString())
                    .eventDescription(description)
                    .details(details)
                    .build());
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        try {
            auditEventLogService.logEvents(pending);
        } catch (RuntimeException ex) {
            log.warn("[record-access] batched cross-hospital disclosure audit failed for {} row(s): {}",
                pending.size(), ex.getMessage());
        }
    }

    private static String dedupeKey(UUID patientId, String sourceHospitalId) {
        return patientId + "|" + sourceHospitalId;
    }

    /**
     * The (patient, source hospital) disclosures this actor already recorded
     * since midnight. A missing actor cannot be matched against anything, so
     * nothing is suppressed for one.
     */
    private Set<String> disclosuresAlreadyRecordedToday(UUID requesterUserId, java.util.Collection<UUID> patientIds) {
        if (requesterUserId == null || patientIds.isEmpty()) {
            return Set.of();
        }
        Set<String> recorded = new java.util.HashSet<>();
        try {
            List<Object[]> rows = auditEventLogRepository.findDisclosureDetailsForActorSince(
                AuditEventType.RECORD_SHARE, requesterUserId, patientIds,
                java.time.LocalDate.now().atStartOfDay());
            for (Object[] row : rows) {
                UUID patientId = (UUID) row[0];
                String details = (String) row[1];
                if (patientId == null || details == null) {
                    continue;
                }
                java.util.regex.Matcher matcher = SOURCE_HOSPITAL_IN_DETAILS.matcher(details);
                if (matcher.find()) {
                    recorded.add(dedupeKey(patientId, matcher.group(1)));
                }
            }
        } catch (RuntimeException ex) {
            // A dedupe that cannot read the past records everything rather
            // than nothing: a duplicate line is a nuisance, a missing
            // disclosure is a defect.
            log.warn("[record-access] could not read today's disclosures for actor {}: {}",
                requesterUserId, ex.getMessage());
        }
        return recorded;
    }

    /**
     * Record one {@code RECORD_SHARE} per entry of {@code perSource}. Empty
     * input records nothing, so callers can pass the reach unconditionally.
     *
     * @param assignmentId the actor's assignment at the acting hospital when
     *                     the caller has it, else {@code null}
     */
    public void recordReach(UUID patientId, UUID actingHospitalId, UUID requesterUserId, UUID assignmentId,
                       Map<String, Long> perSource, String description) {
        if (perSource == null || perSource.isEmpty() || patientId == null) {
            return;
        }
        // E9 #62 — a read under break-the-glass names the session on every row
        // it enabled, so the patient's disclosure report can say why.
        Optional<UUID> breakGlassSessionId = breakGlassGate.liveSessionId(requesterUserId, patientId, actingHospitalId);
        for (Map.Entry<String, Long> reach : perSource.entrySet()) {
            Map<String, Object> details = new HashMap<>();
            details.put(DETAIL_ACTING_HOSPITAL_ID, String.valueOf(actingHospitalId));
            details.put(DETAIL_SOURCE_HOSPITAL_ID, reach.getKey());
            details.put(DETAIL_ROWS_SURFACED, reach.getValue());
            breakGlassSessionId.ifPresent(id -> details.put(DETAIL_BREAK_GLASS_SESSION_ID, id.toString()));
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.RECORD_SHARE)
                    .status(AuditStatus.SUCCESS)
                    .userId(requesterUserId)
                    .assignmentId(assignmentId)
                    .patientId(patientId)
                    .entityType(ENTITY_TYPE_PATIENT)
                    .resourceId(patientId.toString())
                    .eventDescription(description)
                    .details(details)
                    .build());
            } catch (RuntimeException ex) {
                log.warn("[record-access] cross-hospital disclosure audit failed for patient {} source {}: {}",
                    patientId, reach.getKey(), ex.getMessage());
            }
        }
    }
}
