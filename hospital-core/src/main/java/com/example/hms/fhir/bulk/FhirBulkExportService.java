package com.example.hms.fhir.bulk;

import ca.uhn.fhir.rest.server.exceptions.MethodNotAllowedException;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.fhir.FhirOperationsProperties;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Foundation-pass FHIR R4 Bulk Data Access service ($export — roadmap
 * row 21, v1.1 / Backend / Interop FHIR).
 *
 * <p>This pass deliberately ships an <strong>in-memory job map</strong>
 * with status transitions but no NDJSON output. The follow-on (named
 * in the row-21 cell) adds: persistent {@code fhir_bulk_export_jobs}
 * table, a Spring {@code @Scheduled} runner that fans out
 * per-resource-type collection queries through the existing FHIR read
 * mappers, NDJSON writer streaming to an S3-compatible bucket
 * (deliverable target), and the timeout / retry / DLQ semantics the
 * spec implies for a bulk job.
 *
 * <p>Scope-limited for the foundation pass:
 * <ul>
 *   <li>Job creation returns a UUID, sets status to {@code QUEUED}, and
 *       persists nothing — restart wipes the map. Acceptable because
 *       the deliverable target is an S3-backed runner that does not
 *       yet exist; once it lands, both the map and the queued state
 *       are replaced atomically.</li>
 *   <li>Status polling always returns {@code IN_PROGRESS} for the
 *       lifetime of the job — there is no completion path yet. This
 *       is the spec-conformant 202 response while a real job runs;
 *       the only deviation is that the job never advances. Operators
 *       must call {@code cancelExport} to clear the entry.</li>
 *   <li>{@link #cancelExport} flips state to {@code CANCELLED} and
 *       drops the row; subsequent polls return {@code NOT_FOUND}.</li>
 * </ul>
 *
 * <p>Tenant scope: each job is pinned to the active hospital from
 * {@link HospitalContextHolder}; status / cancel calls must come from
 * the same tenant or the lookup returns {@code NOT_FOUND}
 * (cross-tenant rejection is invisible — no information leak).
 *
 * <p>Feature-flagged via
 * {@link FhirOperationsProperties.BulkExport#isEnabled()}; flag-off
 * surfaces as {@code 501 Not Implemented} from
 * {@link FhirBulkExportOperationProvider}.
 */
@Service
public class FhirBulkExportService {

    private static final Logger log = LoggerFactory.getLogger(FhirBulkExportService.class);
    private static final String AUDIT_ENTITY_TYPE = "FHIR_BULK_EXPORT_JOB";

    private final FhirOperationsProperties operationsProperties;
    private final AuditEventLogService auditEventLogService;
    private final Map<UUID, BulkExportJobState> jobs = new ConcurrentHashMap<>();

    public FhirBulkExportService(
        FhirOperationsProperties operationsProperties,
        AuditEventLogService auditEventLogService
    ) {
        this.operationsProperties = operationsProperties;
        this.auditEventLogService = auditEventLogService;
    }

    public boolean isEnabled() {
        return operationsProperties.getBulkExport().isEnabled();
    }

    /**
     * Create a new bulk-export job and return its state. The caller is
     * the operation provider; it converts the returned state's id into
     * the {@code Content-Location} header value.
     *
     * @param scope   the kind / level of export ({@link Scope#SYSTEM},
     *                {@link Scope#PATIENT}, or {@link Scope#GROUP})
     * @param since   optional {@code _since} parameter (resources
     *                changed after this instant)
     * @param types   optional {@code _type} parameter (limit which
     *                resource types are exported)
     * @param groupId mandatory when {@code scope == GROUP}; otherwise null
     */
    public BulkExportJobState createExport(
        Scope scope, Instant since, List<String> types, String groupId
    ) {
        ensureEnabled();
        UUID hospitalId = HospitalContextHolder.getContextOrEmpty().getActiveHospitalId();
        UUID jobId = UUID.randomUUID();
        BulkExportJobState state = new BulkExportJobState(
            jobId,
            hospitalId,
            scope,
            since,
            types == null ? List.of() : List.copyOf(types),
            groupId,
            BulkExportStatus.QUEUED,
            Instant.now(),
            null,
            null
        );
        jobs.put(jobId, state);
        emitAudit(state, "FHIR $export job queued (" + scope + ")");
        return state;
    }

    public Optional<BulkExportJobState> getJob(UUID jobId) {
        BulkExportJobState state = jobs.get(jobId);
        if (state == null) return Optional.empty();
        UUID hospitalId = HospitalContextHolder.getContextOrEmpty().getActiveHospitalId();
        // Cross-tenant rejection collapses to "no such job" so the
        // existence of jobs belonging to other tenants is invisible.
        //
        // DENY on null context (PR #352 Copilot review — Medium): a
        // super-admin without an explicit X-Hospital-Id has
        // activeHospitalId == null. Returning the job in that case
        // would let any super-admin see any tenant's jobs — the
        // inverse of the invisible-rejection contract.
        if (hospitalId == null) return Optional.empty();
        if (state.getHospitalId() == null
            || !state.getHospitalId().equals(hospitalId)) {
            return Optional.empty();
        }
        return Optional.of(state);
    }

    /**
     * Mark a job as {@code CANCELLED} and drop it from the in-memory
     * map. Returns {@code true} when a row was actually removed for
     * this tenant; subsequent calls return {@code false} and the
     * status endpoint reports {@code NOT_FOUND}.
     */
    public boolean cancelExport(UUID jobId) {
        Optional<BulkExportJobState> resolved = getJob(jobId);
        if (resolved.isEmpty()) return false;
        BulkExportJobState state = resolved.get().withStatus(BulkExportStatus.CANCELLED);
        jobs.remove(jobId);
        emitAudit(state, "FHIR $export job cancelled");
        return true;
    }

    /**
     * For tests / operators: visible map size (count of in-flight
     * jobs). Not surfaced on the wire.
     */
    public int activeJobCount() {
        return jobs.size();
    }

    private void ensureEnabled() {
        if (!operationsProperties.getBulkExport().isEnabled()) {
            OperationOutcome outcome = new OperationOutcome();
            outcome.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setCode(OperationOutcome.IssueType.NOTSUPPORTED)
                .setDiagnostics("FHIR $export is disabled — set "
                    + "app.fhir.operations.bulk-export.enabled=true to opt in.");
            // 405 — matches the rest of HMS's flag-off contract
            // (Patient / Encounter / Observation FHIR write paths). The
            // bulk-data spec preference of 501 is a polish item for the
            // row-21 follow-on when the async runner lands.
            throw new MethodNotAllowedException(
                "FHIR $export is disabled.", outcome
            );
        }
    }

    private void emitAudit(BulkExportJobState state, String description) {
        try {
            Map<String, Object> tags = new HashMap<>();
            tags.put("scope", state.getScope().name());
            if (state.getGroupId() != null) tags.put("groupId", state.getGroupId());
            if (state.getSince() != null) tags.put("since", state.getSince().toString());
            tags.put("types", state.getTypes());

            AuditEventRequestDTO request = AuditEventRequestDTO.builder()
                .eventType(AuditEventType.DATA_EXPORT)
                .status(AuditStatus.SUCCESS)
                .entityType(AUDIT_ENTITY_TYPE)
                .resourceId(state.getJobId().toString())
                .eventDescription(description)
                .build();
            auditEventLogService.logEvent(request);
        } catch (RuntimeException ex) {
            log.warn("audit emission failed for FHIR $export job {}: {}",
                state.getJobId(), ex.toString());
        }
    }

    /** Spec-level scope of a single $export invocation. */
    public enum Scope { SYSTEM, PATIENT, GROUP }

    /** Internal state machine for a foundation-pass job. */
    public enum BulkExportStatus { QUEUED, IN_PROGRESS, COMPLETED, FAILED, CANCELLED }
}
