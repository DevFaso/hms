package com.example.hms.fhir.bulk;

import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.NotImplementedOperationException;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.fhir.FhirOperationsProperties;
import com.example.hms.model.platform.FhirBulkExportFile;
import com.example.hms.model.platform.FhirBulkExportJob;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.FhirBulkExportFileRepository;
import com.example.hms.repository.FhirBulkExportJobRepository;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * FHIR R4 Bulk Data Access service ($export — roadmap row 21, completed
 * by P3 #24). The foundation pass shipped an in-memory job map with no
 * runner; this pass replaces it with the persistent
 * {@code platform.fhir_bulk_export_jobs} table swept by
 * {@link FhirBulkExportRunner}, which streams NDJSON to local disk and
 * flips the job COMPLETED so the status endpoint can finally serve the
 * spec's {@code 200 OK + manifest} branch.
 *
 * <p>Tenant scope: each job is pinned to the active hospital from
 * {@link HospitalContextHolder} at creation — creation now REFUSES a
 * missing hospital scope (400), because the foundation pass would
 * happily create a null-tenant job that no status call could ever see
 * again. Status / cancel calls from another tenant collapse to
 * {@code NOT_FOUND} (cross-tenant rejection is invisible).
 *
 * <p>Authorisation: a completed export is a bulk PHI extract, so kickoff
 * requires SUPER_ADMIN or HOSPITAL_ADMIN — checked here because the
 * HAPI servlet space ({@code /fhir/**}) carries no role gate of its
 * own. The status controller mirrors the same gate.
 *
 * <p>Feature-flagged via
 * {@link FhirOperationsProperties.BulkExport#isEnabled()}; flag-off now
 * surfaces as {@code 501 Not Implemented} per the bulk-data spec — the
 * foundation pass returned 405 and documented 501 as landing "with the
 * async runner", which is this change.
 *
 * <p>Cancel never deletes: the row flips to CANCELLED and stays for
 * audit, but polls for a cancelled job return NOT_FOUND per the spec's
 * post-DELETE contract.
 */
@Service
public class FhirBulkExportService {

    private static final Logger log = LoggerFactory.getLogger(FhirBulkExportService.class);
    private static final String AUDIT_ENTITY_TYPE = "FHIR_BULK_EXPORT_JOB";

    /** Resource types the runner knows how to emit — the $everything five. */
    public static final Set<String> SUPPORTED_TYPES = Set.of(
        "Patient", "Encounter", "Observation", "Condition", "MedicationRequest");

    /** Accepted {@code _outputFormat} spellings per the bulk-data spec. */
    private static final Set<String> ACCEPTED_OUTPUT_FORMATS = Set.of(
        "application/fhir+ndjson", "application/ndjson", "ndjson");

    private final FhirOperationsProperties operationsProperties;
    private final AuditEventLogService auditEventLogService;
    private final FhirBulkExportJobRepository jobRepository;
    private final FhirBulkExportFileRepository fileRepository;
    private final RoleValidator roleValidator;
    private final Path storageRoot;

    public FhirBulkExportService(
        FhirOperationsProperties operationsProperties,
        AuditEventLogService auditEventLogService,
        FhirBulkExportJobRepository jobRepository,
        FhirBulkExportFileRepository fileRepository,
        RoleValidator roleValidator
    ) {
        this.operationsProperties = operationsProperties;
        this.auditEventLogService = auditEventLogService;
        this.jobRepository = jobRepository;
        this.fileRepository = fileRepository;
        this.roleValidator = roleValidator;
        this.storageRoot = Paths.get(
            operationsProperties.getBulkExport().getStorageDir()).toAbsolutePath().normalize();
    }

    public boolean isEnabled() {
        return operationsProperties.getBulkExport().isEnabled();
    }

    /**
     * Create a new bulk-export job. The caller is the operation
     * provider; it converts the returned job's id into the
     * {@code Content-Location} header value.
     *
     * @param scope        the kind / level of export
     * @param since        optional {@code _since} (resources changed after this)
     * @param types        optional {@code _type} (limit exported resource types)
     * @param outputFormat optional {@code _outputFormat} — validated, and the
     *                     runner always writes {@code application/fhir+ndjson}
     * @param groupId      mandatory when {@code scope == GROUP}; otherwise null
     * @param requestUrl   the kickoff URL, echoed as {@code request} in the manifest
     */
    @Transactional
    public FhirBulkExportJob createExport(
        FhirBulkExportJob.Scope scope, Instant since, List<String> types,
        String outputFormat, String groupId, String requestUrl
    ) {
        ensureEnabled();
        requireBulkExportRole();
        validateOutputFormat(outputFormat);
        List<String> normalizedTypes = validateTypes(types);

        UUID hospitalId = HospitalContextHolder.getContextOrEmpty().getActiveHospitalId();
        if (hospitalId == null) {
            // The foundation pass created a null-tenant job here — a row
            // the deny-on-null status lookup could never return. Refusing
            // up front turns a silent dead job into an actionable 400.
            throw invalidRequest("FHIR $export requires an active hospital scope; "
                + "supply X-Hospital-Id or authenticate as a hospital-scoped user.");
        }

        FhirBulkExportJob job = jobRepository.save(FhirBulkExportJob.builder()
            .hospitalId(hospitalId)
            .scope(scope)
            .sinceInstant(since)
            .types(normalizedTypes.isEmpty() ? null : String.join(",", normalizedTypes))
            .groupId(groupId)
            .status(FhirBulkExportJob.Status.QUEUED)
            .requestUrl(requestUrl)
            .requestedByUsername(currentUsername())
            .requestedAt(Instant.now())
            .build());
        emitAudit(job, "FHIR $export job queued (" + scope + ")");
        return job;
    }

    /**
     * Tenant-scoped job lookup. Cross-tenant rejection collapses to
     * empty so the existence of jobs belonging to other tenants is
     * invisible; a null active hospital is a DENY for the same reason
     * (PR #352 Copilot review — a super-admin without an explicit
     * X-Hospital-Id must not see every tenant's jobs).
     */
    @Transactional(readOnly = true)
    public Optional<FhirBulkExportJob> getJob(UUID jobId) {
        UUID hospitalId = HospitalContextHolder.getContextOrEmpty().getActiveHospitalId();
        if (hospitalId == null) return Optional.empty();
        return jobRepository.findByIdAndHospitalId(jobId, hospitalId);
    }

    /**
     * Flip a job to CANCELLED. The row is kept (deactivate-never-delete),
     * but the status endpoint reports NOT_FOUND for cancelled jobs per
     * the spec's post-DELETE contract, and the runner aborts a job it
     * sees flip mid-run. Only QUEUED / IN_PROGRESS jobs are cancellable;
     * terminal jobs report {@code false} exactly like unknown ids.
     */
    @Transactional
    public boolean cancelExport(UUID jobId) {
        Optional<FhirBulkExportJob> resolved = getJob(jobId);
        if (resolved.isEmpty()) return false;
        FhirBulkExportJob job = resolved.get();
        if (job.getStatus() != FhirBulkExportJob.Status.QUEUED
            && job.getStatus() != FhirBulkExportJob.Status.IN_PROGRESS) {
            return false;
        }
        job.setStatus(FhirBulkExportJob.Status.CANCELLED);
        job.setCompletedAt(Instant.now());
        jobRepository.save(job);
        emitAudit(job, "FHIR $export job cancelled");
        return true;
    }

    /** Open (non-terminal) job count — surfaced for operators/tests. */
    @Transactional(readOnly = true)
    public long activeJobCount() {
        return jobRepository.countByStatusIn(List.of(
            FhirBulkExportJob.Status.QUEUED, FhirBulkExportJob.Status.IN_PROGRESS));
    }

    /** Manifest lines for a completed job, stable order. */
    @Transactional(readOnly = true)
    public List<FhirBulkExportFile> filesFor(UUID jobId) {
        return fileRepository.findByJob_IdOrderByResourceTypeAsc(jobId);
    }

    /**
     * Resolve one output file for download. The on-disk name comes from
     * the DB row matched by (job, fileName) — never from raw client
     * input — and the resolved path must stay inside the job's own
     * directory, so traversal has nothing to grab onto.
     */
    @Transactional(readOnly = true)
    public Path resolveOutputFile(FhirBulkExportJob job, String fileName) {
        FhirBulkExportFile file = fileRepository
            .findByJob_IdAndFileName(job.getId(), fileName)
            .orElseThrow(() -> new ResourceNotFoundException(
                "No output file '" + fileName + "' on bulk-export job " + job.getId()));
        Path jobDir = storageRoot.resolve(job.getId().toString()).normalize();
        Path path = jobDir.resolve(file.getFileName()).normalize();
        if (!path.startsWith(jobDir) || !Files.exists(path)) {
            throw new ResourceNotFoundException(
                "Output file '" + fileName + "' is no longer on disk for job " + job.getId());
        }
        return path;
    }

    /** Shared kickoff/status role gate — a bulk export is a mass PHI extract. */
    public void requireBulkExportRole() {
        if (!roleValidator.hasAnyAuthority("SUPER_ADMIN", "HOSPITAL_ADMIN")) {
            OperationOutcome outcome = new OperationOutcome();
            outcome.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setCode(OperationOutcome.IssueType.FORBIDDEN)
                .setDiagnostics("FHIR $export requires SUPER_ADMIN or HOSPITAL_ADMIN — "
                    + "a bulk export is a mass PHI extract.");
            throw new ForbiddenOperationException("FHIR $export is restricted.", outcome);
        }
    }

    private void validateOutputFormat(String outputFormat) {
        if (outputFormat == null || outputFormat.isBlank()) return;
        if (!ACCEPTED_OUTPUT_FORMATS.contains(outputFormat.trim())) {
            throw invalidRequest("_outputFormat '" + outputFormat
                + "' is not supported; use application/fhir+ndjson, application/ndjson or ndjson.");
        }
    }

    /**
     * Reject unsupported {@code _type} values at kickoff (the spec's
     * SHOULD) — silently dropping a requested type would let a client
     * believe its extract was complete when it wasn't.
     */
    private List<String> validateTypes(List<String> types) {
        if (types == null || types.isEmpty()) return List.of();
        Set<String> normalized = new LinkedHashSet<>(types);
        for (String type : normalized) {
            if (!SUPPORTED_TYPES.contains(type)) {
                throw invalidRequest("_type '" + type + "' is not supported; supported types: "
                    + String.join(", ", SUPPORTED_TYPES.stream().sorted().toList()) + ".");
            }
        }
        return List.copyOf(normalized);
    }

    private void ensureEnabled() {
        if (!operationsProperties.getBulkExport().isEnabled()) {
            OperationOutcome outcome = new OperationOutcome();
            outcome.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setCode(OperationOutcome.IssueType.NOTSUPPORTED)
                .setDiagnostics("FHIR $export is disabled — set "
                    + "app.fhir.operations.bulk-export.enabled=true to opt in.");
            // 501 per the bulk-data spec. The foundation pass returned 405
            // (the HMS flag-off contract) and documented the flip to 501
            // as shipping "with the async runner" — which is this pass.
            throw new NotImplementedOperationException(
                "FHIR $export is disabled.", outcome
            );
        }
    }

    private static InvalidRequestException invalidRequest(String message) {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(OperationOutcome.IssueType.VALUE)
            .setDiagnostics(message);
        return new InvalidRequestException(message, outcome);
    }

    private static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    void emitAudit(FhirBulkExportJob job, String description) {
        try {
            AuditEventRequestDTO request = AuditEventRequestDTO.builder()
                .eventType(AuditEventType.DATA_EXPORT)
                .status(AuditStatus.SUCCESS)
                .entityType(AUDIT_ENTITY_TYPE)
                .resourceId(job.getId() == null ? null : job.getId().toString())
                .eventDescription(description)
                .build();
            auditEventLogService.logEvent(request);
        } catch (RuntimeException ex) {
            log.warn("audit emission failed for FHIR $export job {}: {}",
                job.getId(), ex.toString());
        }
    }
}
