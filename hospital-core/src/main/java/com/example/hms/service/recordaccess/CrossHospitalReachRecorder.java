package com.example.hms.service.recordaccess;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.service.AuditEventLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
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
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

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
     * of a whole page resolved once and written in one pass, where a
     * per-patient loop cost a committed transaction each — a few hundred
     * patients on a worklist meant a few hundred transactions. The
     * break-glass session is still looked up per patient, because it IS per
     * patient; what batching removes is the transaction per patient, and the
     * repeat lookups when one patient's rows come from several source
     * hospitals.
     *
     * <p>Efficiency only: <strong>every read is recorded</strong>, exactly as
     * {@code getLabOrdersByPatientId} records one. Nothing here suppresses a
     * repeat. An earlier calendar-day dedupe was removed because the
     * accounting has no notion of a repeat anywhere else, and every way of
     * inventing one here under-reported: it matched any RECORD_SHARE for the
     * actor and patient — so an unrelated chart read earlier in the day
     * silenced the worklist disclosure — it left the acting hospital out of
     * the key, so an actor working at two performing hospitals never recorded
     * the second, and it froze {@code rowsSurfaced} at the day's first read.
     *
     * <p>Never throws: an audit failure must not fail the read it accounts
     * for.
     *
     * @param perPatient patient id -> (source hospital id -> rows surfaced)
     */
    public void recordBatchedReach(Map<UUID, Map<String, Long>> perPatient, UUID actingHospitalId,
                                   UUID requesterUserId, UUID assignmentId, String description) {
        if (perPatient == null || perPatient.isEmpty()) {
            return;
        }
        List<AuditEventRequestDTO> pending = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, Long>> patient : perPatient.entrySet()) {
            UUID patientId = patient.getKey();
            if (patientId == null) {
                continue;
            }
            // Per patient, so one patient's failure costs one patient's rows.
            // Wrapping the whole loop meant a single break-glass lookup going
            // down threw away the page's disclosures — worse than the
            // per-patient recorder it replaced, which lost only its own.
            try {
                Optional<UUID> breakGlassSessionId = liveBreakGlassSession(requesterUserId, patientId, actingHospitalId);
                for (Map.Entry<String, Long> reach : patient.getValue().entrySet()) {
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
            } catch (RuntimeException ex) {
                log.warn("[record-access] could not prepare the disclosure of patient {} at hospital {}: {}",
                    patientId, actingHospitalId, ex.getMessage());
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

    /**
     * The break-glass session, read in a transaction of its own.
     *
     * <p>This is a repository read, and the caller is a read-only transaction
     * serving a GET. A database failure inside it would mark that transaction
     * rollback-only; the catch above would swallow the exception and the read
     * would still die at commit, failing the request the accounting exists to
     * account for — the rollback-only trap this project has been bitten by
     * before, and the one {@code LabOrderRoutingNotifier} documents. Suspending
     * the caller's transaction keeps the damage inside this one.
     */
    private Optional<UUID> liveBreakGlassSession(UUID requesterUserId, UUID patientId, UUID actingHospitalId) {
        TransactionTemplate ownTransaction = new TransactionTemplate(transactionManager);
        ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return ownTransaction.execute(status ->
            breakGlassGate.liveSessionId(requesterUserId, patientId, actingHospitalId));
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
