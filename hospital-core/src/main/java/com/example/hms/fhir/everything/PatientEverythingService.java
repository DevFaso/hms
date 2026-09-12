package com.example.hms.fhir.everything;

import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.MethodNotAllowedException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.fhir.FhirOperationsProperties;
import com.example.hms.fhir.mapper.ConditionFhirMapper;
import com.example.hms.fhir.mapper.DocumentReferenceFhirMapper;
import com.example.hms.fhir.mapper.EncounterFhirMapper;
import com.example.hms.fhir.mapper.MedicationRequestFhirMapper;
import com.example.hms.fhir.mapper.ObservationFhirMapper;
import com.example.hms.fhir.mapper.PatientFhirMapper;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientUploadedDocument;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.DischargeSummaryRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientUploadedDocumentRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.security.SecurityUtils;
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
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.CrossHospitalRows;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.example.hms.service.recordaccess.SensitivityClassifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final PatientUploadedDocumentRepository uploadedDocumentRepository;
    private final DischargeSummaryRepository dischargeSummaryRepository;
    private final UserRepository userRepository;
    private final PatientFhirMapper patientMapper;
    private final EncounterFhirMapper encounterMapper;
    private final ObservationFhirMapper observationMapper;
    private final ConditionFhirMapper conditionMapper;
    private final MedicationRequestFhirMapper medicationRequestMapper;
    private final DocumentReferenceFhirMapper documentReferenceMapper;
    private final AuditEventLogService auditEventLogService;
    private final RecordAccessPolicy recordAccessPolicy;
    private final CrossHospitalReachRecorder reachRecorder;
    private final SensitivityClassifier sensitivityClassifier;

    public PatientEverythingService(
        FhirOperationsProperties operationsProperties,
        PatientRepository patientRepository,
        PatientHospitalRegistrationRepository registrationRepository,
        EncounterRepository encounterRepository,
        PatientVitalSignRepository vitalSignRepository,
        LabResultRepository labResultRepository,
        PatientProblemRepository patientProblemRepository,
        PrescriptionRepository prescriptionRepository,
        PatientUploadedDocumentRepository uploadedDocumentRepository,
        DischargeSummaryRepository dischargeSummaryRepository,
        UserRepository userRepository,
        PatientFhirMapper patientMapper,
        EncounterFhirMapper encounterMapper,
        ObservationFhirMapper observationMapper,
        ConditionFhirMapper conditionMapper,
        MedicationRequestFhirMapper medicationRequestMapper,
        DocumentReferenceFhirMapper documentReferenceMapper,
        AuditEventLogService auditEventLogService,
        RecordAccessPolicy recordAccessPolicy,
        CrossHospitalReachRecorder reachRecorder,
        SensitivityClassifier sensitivityClassifier
    ) {
        this.operationsProperties = operationsProperties;
        this.patientRepository = patientRepository;
        this.registrationRepository = registrationRepository;
        this.encounterRepository = encounterRepository;
        this.vitalSignRepository = vitalSignRepository;
        this.labResultRepository = labResultRepository;
        this.patientProblemRepository = patientProblemRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.uploadedDocumentRepository = uploadedDocumentRepository;
        this.dischargeSummaryRepository = dischargeSummaryRepository;
        this.userRepository = userRepository;
        this.patientMapper = patientMapper;
        this.encounterMapper = encounterMapper;
        this.observationMapper = observationMapper;
        this.conditionMapper = conditionMapper;
        this.medicationRequestMapper = medicationRequestMapper;
        this.documentReferenceMapper = documentReferenceMapper;
        this.auditEventLogService = auditEventLogService;
        this.recordAccessPolicy = recordAccessPolicy;
        this.reachRecorder = reachRecorder;
        this.sensitivityClassifier = sensitivityClassifier;
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
        java.util.Set<String> seenEntryKeys = new java.util.HashSet<>();
        for (int page = 0; page < 40; page++) {
            Bundle chunk = doEverythingForPatient(patientId,
                PatientEverythingParams.of(null, null, PatientEverythingParams.MAX_COUNT, cursor));
            if (merged == null) {
                merged = chunk;
                merged.getEntry().forEach(e -> seenEntryKeys.add(entryKey(e)));
            } else {
                appendNewEntries(merged, chunk, seenEntryKeys);
            }
            cursor = nextCursorOf(chunk);
            if (cursor == null) break;
        }
        if (cursor != null) {
            // A partial file that LOOKS complete is worse than no file: the
            // reader treats it as "the whole record". Fail loudly instead.
            throw new IllegalStateException(
                "Patient record export exceeded the 40-page safety limit — refusing to "
                    + "return a partial file that would masquerade as the full record.");
        }
        merged.getLink().removeIf(l -> "next".equals(l.getRelation()));
        merged.setTotal(merged.getEntry().size());
        return merged;
    }

    /**
     * Appends only entries not seen on earlier pages, keyed by
     * resourceType/id. Belt to the first-page gating on unpaged sections:
     * any section that re-emits across cursor iterations would otherwise
     * duplicate its rows in the merged file. Package-visible for its test.
     */
    static void appendNewEntries(Bundle merged, Bundle chunk, java.util.Set<String> seenEntryKeys) {
        for (Bundle.BundleEntryComponent entry : chunk.getEntry()) {
            String key = entryKey(entry);
            if (key == null || seenEntryKeys.add(key)) {
                merged.getEntry().add(entry);
            }
        }
    }

    private static String entryKey(Bundle.BundleEntryComponent entry) {
        Resource resource = entry.getResource();
        if (resource == null || resource.getIdElement().getIdPart() == null) {
            return null;
        }
        return resource.fhirType() + "/" + resource.getIdElement().getIdPart();
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

        // E9 #60b — the bundle follows the patient: every section reads the
        // policy's readable set for this patient (the acting hospital alone
        // when the policy says so), a foreign encounter or condition in a
        // sensitive category is withheld (D3), and the reach of the page is
        // accounted once per source hospital beside the export audit.
        User actor = resolveExportActor();
        UUID requesterUserId = actor != null ? actor.getId()
            : HospitalContextHolder.getContextOrEmpty().getPrincipalUserId();
        Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, hospitalId);
        SectionContext ctx = SectionContext.forRequest(patientId, hospitalId, readable, params);
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);

        appendPatientSection(bundle, patient, ctx);
        appendEncounterSection(bundle, ctx);
        appendObservationSection(bundle, ctx);
        appendConditionSection(bundle, ctx);
        appendMedicationRequestSection(bundle, ctx);
        appendDocumentReferenceSection(bundle, ctx);

        bundle.setTotal(bundle.getEntry().size());
        if (ctx.hasMore()) {
            bundle.addLink()
                .setRelation("next")
                .setUrl(nextLink(patientId, params, ctx.nextCursor()));
        }

        reachRecorder.recordReach(patientId, hospitalId, requesterUserId, null, ctx.reach(),
            "Cross-hospital FHIR $everything read on the treatment relationship");
        emitAudit(patient, describe(patientId, params, bundle.getTotal()));
        return bundle;
    }

    private void appendDocumentReferenceSection(Bundle bundle, SectionContext ctx) {
        if (!ctx.includes("DocumentReference")) return;
        // Uploaded documents are patient-anchored (no hospital column); the
        // registration gate in loadAndVerifyTenantOwnedPatient already ran.
        Page<PatientUploadedDocument> docs = uploadedDocumentRepository
            .findByPatient_IdAndDeletedAtIsNullOrderByCreatedAtDesc(ctx.patientId(), ctx.pageRequest());
        ctx.notePageOverflow(docs);
        docs.forEach(d -> {
            if (ctx.passesSinceFilter(d.getUpdatedAt())) {
                addEntry(bundle, documentReferenceMapper.toFhir(d));
            }
        });
        // Discharge summaries are few per patient — unpaged, first page only
        // (same rule as the Condition section).
        if (ctx.isFirstPage()) {
            List<com.example.hms.model.discharge.DischargeSummary> summaries = dischargeSummaryRepository
                .findWithAssociationsByPatient_IdAndHospital_IdInOrderByDischargeDateDesc(
                    ctx.patientId(), ctx.readable());
            ctx.account(summaries.stream().map(s -> CrossHospitalReachRecorder.hospitalIdOf(s.getHospital())).toList());
            summaries.stream()
                .filter(sSummary -> ctx.passesSinceFilter(sSummary.getUpdatedAt()))
                .forEach(sSummary -> addEntry(bundle, documentReferenceMapper.toFhir(sSummary)));
        }
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
        Page<com.example.hms.model.Encounter> page = encounterRepository
            .findByPatient_IdAndHospital_IdInOrderByEncounterDateDesc(ctx.patientId(), ctx.readable(), ctx.pageRequest());
        ctx.notePageOverflow(page);
        List<com.example.hms.model.Encounter> surfaced = page.getContent().stream()
            .filter(e -> CrossHospitalRows.maySurface(e.getHospital(), ctx.hospitalId(), sensitivityClassifier.effectiveCategory(e)))
            .toList();
        ctx.account(surfaced.stream().map(e -> CrossHospitalReachRecorder.hospitalIdOf(e.getHospital())).toList());
        surfaced.forEach(encounter -> {
            if (ctx.passesSinceFilter(encounter.getUpdatedAt())) {
                addEntry(bundle, encounterMapper.toFhir(encounter));
            }
        });
    }

    private void appendObservationSection(Bundle bundle, SectionContext ctx) {
        if (!ctx.includes("Observation")) return;
        Page<com.example.hms.model.PatientVitalSign> vitals = vitalSignRepository
            .findPageByPatient_IdAndHospital_IdInOrderByRecordedAtDesc(
                ctx.patientId(), ctx.readable(), ctx.pageRequest());
        ctx.notePageOverflow(vitals);
        ctx.account(vitals.getContent().stream().map(v -> CrossHospitalReachRecorder.hospitalIdOf(v.getHospital())).toList());
        vitals.forEach(v -> {
            if (ctx.passesSinceFilter(v.getUpdatedAt())) {
                observationMapper.toFhir(v).forEach(o -> addEntry(bundle, o));
            }
        });
        Page<com.example.hms.model.LabResult> labResults = labResultRepository
            .findPageByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn(
                ctx.patientId(), ctx.readable(), ctx.pageRequest());
        ctx.notePageOverflow(labResults);
        ctx.account(labResults.getContent().stream()
            .map(r -> r.getLabOrder() == null ? null : CrossHospitalReachRecorder.hospitalIdOf(r.getLabOrder().getHospital()))
            .toList());
        labResults.forEach(r -> {
            if (ctx.passesSinceFilter(r.getUpdatedAt())) {
                addEntry(bundle, observationMapper.toFhir(r));
            }
        });
    }

    private void appendConditionSection(Bundle bundle, SectionContext ctx) {
        // Unpaged section: emit ONLY on the first page, or every cursor
        // iteration repeats the complete problem list (duplicate entries in
        // paged responses and in the merged download alike).
        if (!ctx.includes("Condition") || !ctx.isFirstPage()) return;
        // The readable set, not the acting hospital alone (E9 #60b); a foreign
        // problem in a sensitive category is withheld (D3).
        List<com.example.hms.model.PatientProblem> problems = patientProblemRepository
            .findByPatient_IdAndHospital_IdIn(ctx.patientId(), ctx.readable()).stream()
            .filter(c -> CrossHospitalRows.maySurface(c.getHospital(), ctx.hospitalId(), sensitivityClassifier.effectiveCategory(c)))
            .toList();
        ctx.account(problems.stream().map(c -> CrossHospitalReachRecorder.hospitalIdOf(c.getHospital())).toList());
        problems.stream()
            .filter(c -> ctx.passesSinceFilter(c.getUpdatedAt()))
            .forEach(c -> addEntry(bundle, conditionMapper.toFhir(c)));
    }

    private void appendMedicationRequestSection(Bundle bundle, SectionContext ctx) {
        if (!ctx.includes("MedicationRequest")) return;
        Page<com.example.hms.model.Prescription> prescriptions = prescriptionRepository
            .findByPatient_IdAndHospital_IdIn(ctx.patientId(), ctx.readable(), ctx.pageRequest());
        ctx.notePageOverflow(prescriptions);
        ctx.account(prescriptions.getContent().stream().map(p -> CrossHospitalReachRecorder.hospitalIdOf(p.getHospital())).toList());
        prescriptions.forEach(p -> {
            if (ctx.passesSinceFilter(p.getUpdatedAt())) {
                addEntry(bundle, medicationRequestMapper.toFhir(p));
            }
        });
    }

    /**
     * Best-effort actor for the PHI-export audit trail; null when the
     * principal has no local row (falls back to SYSTEM downstream). Public
     * only because its test lives beside the other tenant-gate tests in the
     * parent package.
     */
    public User resolveExportActor() {
        String username = SecurityUtils.getCurrentUsername();
        if (username == null || username.isBlank()) {
            return null;
        }
        return userRepository.findByUsernameIgnoreCase(username).orElse(null);
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
        // Deliberately identical whether the patient does not exist at all or
        // exists at another tenant — otherwise the wording itself discloses
        // that someone by this id is a patient somewhere else. The second
        // sentence is the remedy, because the caller most often just has the
        // wrong hospital selected.
        String message = PATIENT_PREFIX + patientId
            + " is not registered at your active hospital. If you expect this record, "
            + "switch your hospital scope to one where the patient is registered.";
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
            // Without an explicit actor the audit sink records the exporter
            // as SYSTEM — useless for a PHI-export trail. Best-effort
            // resolution from the security context; a FHIR client whose
            // principal has no local row still falls back to SYSTEM.
            User actor = resolveExportActor();
            AuditEventRequestDTO request = AuditEventRequestDTO.builder()
                .eventType(AuditEventType.PATIENT_EXPORT)
                .status(AuditStatus.SUCCESS)
                .entityType(AUDIT_ENTITY_TYPE)
                .userId(actor != null ? actor.getId() : null)
                .userName(actor != null ? actor.getUsername() : SecurityUtils.getCurrentUsername())
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
        private final Set<UUID> readable;
        private final PatientEverythingParams params;
        private final PageRequest pageRequest;
        private final Map<String, Long> reach = new HashMap<>();
        private boolean hasMore;

        private SectionContext(UUID patientId, UUID hospitalId, Set<UUID> readable, PatientEverythingParams params) {
            this.patientId = patientId;
            this.hospitalId = hospitalId;
            this.readable = readable;
            this.params = params;
            this.pageRequest = PageRequest.of(params.cursor(), params.count());
        }

        static SectionContext forRequest(UUID patientId, UUID hospitalId, Set<UUID> readable, PatientEverythingParams params) {
            return new SectionContext(patientId, hospitalId, readable, params);
        }

        UUID patientId() { return patientId; }
        UUID hospitalId() { return hospitalId; }
        /** E9 #60b — the hospitals every section reads (RecordAccessPolicy.readableHospitalIds). */
        Set<UUID> readable() { return readable; }
        /** E9 #60b — one entry per foreign hospital surfaced on this page, across every section. */
        Map<String, Long> reach() { return reach; }
        void account(List<UUID> sourceHospitalIds) {
            CrossHospitalReachRecorder.merge(reach, CrossHospitalReachRecorder.reachOf(sourceHospitalIds, hospitalId));
        }
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
