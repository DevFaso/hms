package com.example.hms.fhir.everything;

import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.MethodNotAllowedException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.fhir.FhirOperationsProperties;
import com.example.hms.fhir.mapper.ConditionFhirMapper;
import com.example.hms.fhir.mapper.EncounterFhirMapper;
import com.example.hms.fhir.mapper.MedicationRequestFhirMapper;
import com.example.hms.fhir.mapper.ObservationFhirMapper;
import com.example.hms.fhir.mapper.PatientFhirMapper;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Patient-compartment {@code $everything} operation (roadmap row 22,
 * v1.1 / Backend / Interop FHIR).
 *
 * <p>Assembles a single FHIR {@link Bundle} of type {@code searchset}
 * containing the requested Patient plus all linked clinical resources
 * the consumer (typically an HIE handshake) needs in one round-trip.
 *
 * <p>Composition (page-limited per resource type):
 * <ul>
 *   <li>1 {@code Patient}</li>
 *   <li>Most-recent {@code Encounter}s (page-size = {@code _count})</li>
 *   <li>Most-recent vital-sign rows (expanded 1:N into Observation
 *       resources by the existing mapper)</li>
 *   <li>Most-recent lab results (Observation)</li>
 *   <li>Hospital-scoped Conditions (problem list)</li>
 *   <li>Most-recent prescriptions (MedicationRequest)</li>
 * </ul>
 *
 * <p>Tenant scope: read from {@link HospitalContextHolder}. Missing
 * active hospital → {@code 403 Forbidden}. The patient must be
 * registered at the active hospital — cross-tenant access is rejected
 * via {@code findByIdAndHospital_Id} on the per-resource queries; for
 * the Patient lookup itself the tenant gate is enforced via the
 * registration check on the loaded entity.
 *
 * <p>Feature-flagged via
 * {@link FhirOperationsProperties.Everything#isEnabled()}; flag-off
 * surfaces as {@code 405 Method Not Allowed} + a FHIR
 * {@code OperationOutcome} (NOTSUPPORTED).
 */
@Service
public class PatientEverythingService {

    private static final Logger log = LoggerFactory.getLogger(PatientEverythingService.class);
    private static final String AUDIT_ENTITY_TYPE = "PATIENT";
    /**
     * Shared {@code Patient/} resource-id prefix. Extracted as a
     * constant per Sonar S1192 — previously inlined three times in
     * this file and once in the audit description.
     */
    private static final String PATIENT_PREFIX = "Patient/";

    private final FhirOperationsProperties operationsProperties;
    private final PatientRepository patientRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final EncounterRepository encounterRepository;
    private final PatientVitalSignRepository vitalSignRepository;
    private final LabResultRepository labResultRepository;
    private final PatientProblemRepository patientProblemRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final PatientFhirMapper patientMapper;
    private final EncounterFhirMapper encounterMapper;
    private final ObservationFhirMapper observationMapper;
    private final ConditionFhirMapper conditionMapper;
    private final MedicationRequestFhirMapper medicationRequestMapper;
    private final AuditEventLogService auditEventLogService;

    public PatientEverythingService(
        FhirOperationsProperties operationsProperties,
        PatientRepository patientRepository,
        PatientHospitalRegistrationRepository registrationRepository,
        EncounterRepository encounterRepository,
        PatientVitalSignRepository vitalSignRepository,
        LabResultRepository labResultRepository,
        PatientProblemRepository patientProblemRepository,
        PrescriptionRepository prescriptionRepository,
        PatientFhirMapper patientMapper,
        EncounterFhirMapper encounterMapper,
        ObservationFhirMapper observationMapper,
        ConditionFhirMapper conditionMapper,
        MedicationRequestFhirMapper medicationRequestMapper,
        AuditEventLogService auditEventLogService
    ) {
        this.operationsProperties = operationsProperties;
        this.patientRepository = patientRepository;
        this.registrationRepository = registrationRepository;
        this.encounterRepository = encounterRepository;
        this.vitalSignRepository = vitalSignRepository;
        this.labResultRepository = labResultRepository;
        this.patientProblemRepository = patientProblemRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.patientMapper = patientMapper;
        this.encounterMapper = encounterMapper;
        this.observationMapper = observationMapper;
        this.conditionMapper = conditionMapper;
        this.medicationRequestMapper = medicationRequestMapper;
        this.auditEventLogService = auditEventLogService;
    }

    public boolean isEnabled() {
        return operationsProperties.getEverything().isEnabled();
    }

    /**
     * Foundation entry-point — equivalent to {@link #everythingForPatient(UUID, PatientEverythingParams)}
     * with no filters, default count, no cursor. Preserved for callers
     * that haven't migrated to the params-aware overload yet.
     */
    @Transactional(readOnly = true)
    public Bundle everythingForPatient(UUID patientId) {
        ensureEnabled();
        return doEverythingForPatient(patientId,
            PatientEverythingParams.of(null, null, null, null));
    }

    /**
     * Row-22 follow-on: parameterised $everything supporting
     * {@code _since} / {@code _type} / {@code _count} / {@code _page}.
     */
    @Transactional(readOnly = true)
    public Bundle everythingForPatient(UUID patientId, PatientEverythingParams params) {
        ensureEnabled();
        return doEverythingForPatient(patientId, params);
    }

    /**
     * Tier 2 item 44 — the portal's "download my record" export. Pages
     * through every section at {@link PatientEverythingParams#MAX_COUNT}
     * and merges into ONE bundle, because a downloaded file cannot follow
     * a {@code next} link. Deliberately NOT behind
     * {@code app.fhir.operations.everything.enabled}: that flag gates the
     * EXTERNAL FHIR operation surface, while this is an internal,
     * role-gated, audited portal endpoint — tenancy and the registration
     * gate still apply on every page. Each page emits its own audit row,
     * so the export trail shows exactly what left the system.
     */
    @Transactional(readOnly = true)
    public Bundle fullRecordForDownload(UUID patientId) {
        Bundle merged = null;
        Integer cursor = null;
        // Hard page cap: 40 pages x 500/section is far beyond any real
        // record, and it turns a cursor bug into a bounded file instead of
        // an unbounded loop.
        for (int page = 0; page < 40; page++) {
            Bundle chunk = doEverythingForPatient(patientId,
                PatientEverythingParams.of(null, null, PatientEverythingParams.MAX_COUNT, cursor));
            if (merged == null) {
                merged = chunk;
            } else {
                chunk.getEntry().forEach(merged.getEntry()::add);
            }
            cursor = nextCursorOf(chunk);
            if (cursor == null) break;
        }
        merged.getLink().removeIf(l -> "next".equals(l.getRelation()));
        merged.setTotal(merged.getEntry().size());
        return merged;
    }

    /** Reads the {@code _page} cursor back out of the bundle's own next link. Package-visible for its test. */
    static Integer nextCursorOf(Bundle bundle) {
        return bundle.getLink().stream()
            .filter(l -> "next".equals(l.getRelation()) && l.getUrl() != null)
            .findFirst()
            .map(l -> {
                java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("_page=(\\d+)").matcher(l.getUrl());
                return m.find() ? Integer.valueOf(m.group(1)) : null;
            })
            .orElse(null);
    }

    /**
     * Shared implementation behind both public overloads. Kept private
     * so each overload's {@code @Transactional} entry-point goes
     * through Spring's proxy without self-calling — Sonar S2229.
     */
    private Bundle doEverythingForPatient(UUID patientId, PatientEverythingParams params) {
        UUID hospitalId = resolveHospitalScopeOrForbid();
        Patient patient = loadAndVerifyTenantOwnedPatient(patientId, hospitalId);

        SectionContext ctx = SectionContext.forRequest(patientId, hospitalId, params);
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);

        appendPatientSection(bundle, patient, ctx);
        appendEncounterSection(bundle, ctx);
        appendObservationSection(bundle, ctx);
        appendConditionSection(bundle, ctx);
        appendMedicationRequestSection(bundle, ctx);

        bundle.setTotal(bundle.getEntry().size());
        if (ctx.hasMore()) {
            bundle.addLink()
                .setRelation("next")
                .setUrl(nextLink(patientId, params, ctx.nextCursor()));
        }

        emitAudit(patient, describe(patientId, params, bundle.getTotal()));
        return bundle;
    }

    private UUID resolveHospitalScopeOrForbid() {
        UUID hospitalId = HospitalContextHolder.getContextOrEmpty().getActiveHospitalId();
        if (hospitalId == null) {
            throw forbidden(
                "FHIR Patient/{id}/$everything requires an active hospital scope; "
                    + "supply X-Hospital-Id or authenticate as a hospital-scoped user."
            );
        }
        return hospitalId;
    }

    private Patient loadAndVerifyTenantOwnedPatient(UUID patientId, UUID hospitalId) {
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> notFoundForPatient(patientId));
        // Tenant gate: PatientRepository.findById is NOT tenant-aware.
        // The per-resource queries below ARE hospital-scoped, but the
        // Patient resource itself (PHI) would leak across tenants
        // without this check. Cross-tenant rejection collapses to
        // "no such patient" so the existence of patients at other
        // tenants stays invisible.
        boolean registered = registrationRepository
            .findByPatientIdAndHospitalId(patientId, hospitalId)
            .isPresent();
        if (!registered) {
            throw notFoundForPatient(patientId);
        }
        return patient;
    }

    private void appendPatientSection(Bundle bundle, Patient patient, SectionContext ctx) {
        // Patient itself is always emitted on the first page unless
        // _type explicitly excludes it; subsequent pages skip the
        // Patient entry to avoid duplicate emission across cursor
        // iterations.
        if (ctx.includes("Patient") && ctx.isFirstPage()
            && ctx.passesSinceFilter(patient.getUpdatedAt())) {
            addEntry(bundle, patientMapper.toFhir(patient));
        }
    }

    private void appendEncounterSection(Bundle bundle, SectionContext ctx) {
        if (!ctx.includes("Encounter")) return;
        Page<?> page = encounterRepository
            .findByPatient_IdAndHospital_Id(ctx.patientId(), ctx.hospitalId(), ctx.pageRequest());
        ctx.notePageOverflow(page);
        page.forEach(e -> {
            var encounter = (com.example.hms.model.Encounter) e;
            if (ctx.passesSinceFilter(encounter.getUpdatedAt())) {
                addEntry(bundle, encounterMapper.toFhir(encounter));
            }
        });
    }

    private void appendObservationSection(Bundle bundle, SectionContext ctx) {
        if (!ctx.includes("Observation")) return;
        Page<com.example.hms.model.PatientVitalSign> vitals = vitalSignRepository
            .findPageByPatient_IdAndHospital_IdOrderByRecordedAtDesc(
                ctx.patientId(), ctx.hospitalId(), ctx.pageRequest());
        ctx.notePageOverflow(vitals);
        vitals.forEach(v -> {
            if (ctx.passesSinceFilter(v.getUpdatedAt())) {
                observationMapper.toFhir(v).forEach(o -> addEntry(bundle, o));
            }
        });
        Page<com.example.hms.model.LabResult> labResults = labResultRepository
            .findPageByLabOrder_Patient_IdAndLabOrder_Hospital_Id(
                ctx.patientId(), ctx.hospitalId(), ctx.pageRequest());
        ctx.notePageOverflow(labResults);
        labResults.forEach(r -> {
            if (ctx.passesSinceFilter(r.getUpdatedAt())) {
                addEntry(bundle, observationMapper.toFhir(r));
            }
        });
    }

    private void appendConditionSection(Bundle bundle, SectionContext ctx) {
        if (!ctx.includes("Condition")) return;
        // Hospital-scoped per Copilot review (PR copilot-review) — the
        // previous findByPatient_Id call could leak problems recorded
        // at other hospitals for the same patient into a
        // hospital-scoped $everything response.
        patientProblemRepository.findByPatient_IdAndHospital_Id(ctx.patientId(), ctx.hospitalId())
            .stream()
            .filter(c -> ctx.passesSinceFilter(c.getUpdatedAt()))
            .forEach(c -> addEntry(bundle, conditionMapper.toFhir(c)));
    }

    private void appendMedicationRequestSection(Bundle bundle, SectionContext ctx) {
        if (!ctx.includes("MedicationRequest")) return;
        Page<com.example.hms.model.Prescription> prescriptions = prescriptionRepository
            .findByPatient_IdAndHospital_Id(ctx.patientId(), ctx.hospitalId(), ctx.pageRequest());
        ctx.notePageOverflow(prescriptions);
        prescriptions.forEach(p -> {
            if (ctx.passesSinceFilter(p.getUpdatedAt())) {
                addEntry(bundle, medicationRequestMapper.toFhir(p));
            }
        });
    }

    private static String nextLink(UUID patientId, PatientEverythingParams params, int nextCursor) {
        StringBuilder sb = new StringBuilder(PATIENT_PREFIX).append(patientId).append("/$everything?");
        sb.append("_page=").append(nextCursor);
        sb.append("&_count=").append(params.count());
        if (params.since() != null) {
            sb.append("&_since=").append(params.since().toString());
        }
        if (!params.types().isEmpty()) {
            sb.append("&_type=").append(String.join(",", params.types()));
        }
        return sb.toString();
    }

    private static String describe(UUID patientId, PatientEverythingParams params, int entryCount) {
        StringBuilder sb = new StringBuilder("FHIR ")
            .append(PATIENT_PREFIX)
            .append(patientId)
            .append("/$everything returned a ")
            .append(entryCount)
            .append("-entry Bundle");
        if (params.since() != null) {
            sb.append(" since=").append(params.since());
        }
        if (!params.types().isEmpty()) {
            sb.append(" types=").append(String.join(",", params.types()));
        }
        sb.append(" count=").append(params.count())
            .append(" cursor=").append(params.cursor());
        return sb.toString();
    }

    private static void addEntry(Bundle bundle, Resource resource) {
        if (resource == null) return;
        bundle.addEntry().setResource(resource);
    }

    private void ensureEnabled() {
        if (!operationsProperties.getEverything().isEnabled()) {
            OperationOutcome outcome = new OperationOutcome();
            outcome.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setCode(OperationOutcome.IssueType.NOTSUPPORTED)
                .setDiagnostics("FHIR Patient/{id}/$everything is disabled — set "
                    + "app.fhir.operations.everything.enabled=true to opt in.");
            throw new MethodNotAllowedException(
                "FHIR Patient/{id}/$everything is disabled.", outcome
            );
        }
    }

    private static ResourceNotFoundException notFoundForPatient(UUID patientId) {
        String message = PATIENT_PREFIX + patientId + " not found at the active hospital scope.";
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(OperationOutcome.IssueType.NOTFOUND)
            .setDiagnostics(message);
        return new ResourceNotFoundException(message, outcome);
    }

    private static ForbiddenOperationException forbidden(String message) {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(OperationOutcome.IssueType.FORBIDDEN)
            .setDiagnostics(message);
        return new ForbiddenOperationException(message, outcome);
    }

    private void emitAudit(Patient patient, String description) {
        try {
            AuditEventRequestDTO request = AuditEventRequestDTO.builder()
                .eventType(AuditEventType.PATIENT_EXPORT)
                .status(AuditStatus.SUCCESS)
                .entityType(AUDIT_ENTITY_TYPE)
                .resourceId(patient.getId() == null ? null : patient.getId().toString())
                .eventDescription(description)
                .build();
            auditEventLogService.logEvent(request);
        } catch (RuntimeException ex) {
            log.warn("audit emission failed for FHIR {}{}/$everything: {}",
                PATIENT_PREFIX, patient.getId(), ex.toString());
        }
    }

    /**
     * Per-request state carried across the per-section helpers. Keeps
     * the orchestrator method below the Sonar cognitive-complexity
     * limit (S3776) and replaces the original {@code boolean[]{false}}
     * mutable-holder pattern that triggered the "Brain Method" finding.
     */
    private static final class SectionContext {
        private final UUID patientId;
        private final UUID hospitalId;
        private final PatientEverythingParams params;
        private final PageRequest pageRequest;
        private boolean hasMore;

        private SectionContext(UUID patientId, UUID hospitalId, PatientEverythingParams params) {
            this.patientId = patientId;
            this.hospitalId = hospitalId;
            this.params = params;
            this.pageRequest = PageRequest.of(params.cursor(), params.count());
        }

        static SectionContext forRequest(UUID patientId, UUID hospitalId, PatientEverythingParams params) {
            return new SectionContext(patientId, hospitalId, params);
        }

        UUID patientId() { return patientId; }
        UUID hospitalId() { return hospitalId; }
        PageRequest pageRequest() { return pageRequest; }
        boolean isFirstPage() { return params.cursor() == 0; }
        boolean includes(String type) { return params.includes(type); }
        boolean hasMore() { return hasMore; }
        int nextCursor() { return params.cursor() + 1; }

        boolean passesSinceFilter(java.time.LocalDateTime updatedAt) {
            if (params.since() == null) return true;
            Instant resolved = updatedAt == null ? null : updatedAt.toInstant(ZoneOffset.UTC);
            return params.afterSince(resolved);
        }

        void notePageOverflow(Page<?> page) {
            if (page != null && page.hasNext()) {
                this.hasMore = true;
            }
        }
    }
}
