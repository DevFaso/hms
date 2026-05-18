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
 *   <li>Up to {@link #ENCOUNTER_LIMIT} most-recent {@code Encounter}s</li>
 *   <li>Up to {@link #VITAL_LIMIT} most-recent vital-sign rows (expanded
 *       1:N into Observation resources by the existing mapper)</li>
 *   <li>Up to {@link #LAB_LIMIT} most-recent lab results (Observation)</li>
 *   <li>All Conditions (problem list)</li>
 *   <li>Up to {@link #PRESCRIPTION_LIMIT} most-recent prescriptions
 *       (MedicationRequest)</li>
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
    // Page sizes are no longer hard-coded — the row-22 follow-on
    // routes them through PatientEverythingParams.count() so each
    // request can negotiate via _count. The original 200-per-section
    // foundation cap is preserved as PatientEverythingParams.DEFAULT_COUNT.

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
        return everythingForPatient(patientId,
            PatientEverythingParams.of(null, null, null, null));
    }

    /**
     * Row-22 follow-on: parameterised $everything supporting
     * {@code _since} / {@code _type} / {@code _count} / {@code _page}.
     * Each per-resource section is gated by {@code params.includes(type)};
     * resources whose mapped {@code Resource.meta.lastUpdated} is before
     * {@code params.since} are dropped post-mapping; the per-section
     * page size is {@code params.count()}; the optional cursor offsets
     * each per-section page by {@code params.cursor() * params.count()}
     * entries. When any section returned a full page the resulting
     * {@link Bundle} carries a {@code link[next]} entry whose URL
     * advances the cursor by one — same shape FHIR Bulk Data Access
     * uses for continuation.
     */
    @Transactional(readOnly = true)
    public Bundle everythingForPatient(UUID patientId, PatientEverythingParams params) {
        ensureEnabled();
        UUID hospitalId = HospitalContextHolder.getContextOrEmpty().getActiveHospitalId();
        if (hospitalId == null) {
            throw forbidden(
                "FHIR Patient/{id}/$everything requires an active hospital scope; "
                    + "supply X-Hospital-Id or authenticate as a hospital-scoped user."
            );
        }

        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> notFound(
                "Patient/" + patientId + " not found at the active hospital scope.",
                OperationOutcome.IssueType.NOTFOUND
            ));

        // Tenant gate (PR #352 review — High severity Copilot finding).
        // PatientRepository.findById is NOT tenant-aware; the per-resource
        // queries below ARE hospital-scoped, but the Patient resource
        // itself (name, DOB, address, phone, email — all PHI) would leak
        // across tenants without this check. The cross-tenant rejection
        // collapses to "no such patient" so the existence of patients at
        // other tenants stays invisible — same trust call as the
        // empi-identity skill's "Never auto-create a Patient from an
        // unknown EMPI alias".
        boolean registered = registrationRepository
            .findByPatientIdAndHospitalId(patientId, hospitalId)
            .isPresent();
        if (!registered) {
            throw notFound(
                "Patient/" + patientId + " not found at the active hospital scope.",
                OperationOutcome.IssueType.NOTFOUND
            );
        }

        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        // PageRequest offset = cursor × count, so cursor=0 returns
        // page-0 entries, cursor=1 returns page-1 entries, etc.
        int pageSize = params.count();
        int pageOffset = params.cursor();
        PageRequest sectionPage = PageRequest.of(pageOffset, pageSize);
        boolean[] hasMore = new boolean[]{false};

        // Patient itself is always emitted on the first page unless
        // _type explicitly excludes it; subsequent pages skip the
        // Patient entry to avoid duplicate emission across cursor
        // iterations.
        if (params.includes("Patient") && pageOffset == 0
            && passesSinceFilter(params, patient.getUpdatedAt())) {
            addEntry(bundle, patientMapper.toFhir(patient));
        }

        if (params.includes("Encounter")) {
            var encounters = encounterRepository
                .findByPatient_IdAndHospital_Id(patientId, hospitalId, sectionPage);
            if (sectionHasMore(encounters)) hasMore[0] = true;
            encounters.stream()
                .filter(e -> passesSinceFilter(params, e.getUpdatedAt()))
                .forEach(e -> addEntry(bundle, encounterMapper.toFhir(e)));
        }

        if (params.includes("Observation")) {
            var vitals = vitalSignRepository
                .findByPatient_IdAndHospital_IdOrderByRecordedAtDesc(patientId, hospitalId, sectionPage);
            if (sectionHasMore(vitals)) hasMore[0] = true;
            vitals.stream()
                .filter(v -> passesSinceFilter(params, v.getUpdatedAt()))
                .forEach(v -> observationMapper.toFhir(v).forEach(o -> addEntry(bundle, o)));
            var labResults = labResultRepository
                .findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(patientId, hospitalId, sectionPage);
            if (sectionHasMore(labResults)) hasMore[0] = true;
            labResults.stream()
                .filter(r -> passesSinceFilter(params, r.getUpdatedAt()))
                .forEach(r -> addEntry(bundle, observationMapper.toFhir(r)));
        }

        if (params.includes("Condition")) {
            // Conditions are typically a small, stable list — no
            // pagination on the foundation query. The since filter
            // still applies.
            patientProblemRepository.findByPatient_Id(patientId).stream()
                .filter(c -> passesSinceFilter(params, c.getUpdatedAt()))
                .forEach(c -> addEntry(bundle, conditionMapper.toFhir(c)));
        }

        if (params.includes("MedicationRequest")) {
            var prescriptions = prescriptionRepository
                .findByPatient_IdAndHospital_Id(patientId, hospitalId, sectionPage);
            if (sectionHasMore(prescriptions)) hasMore[0] = true;
            prescriptions.stream()
                .filter(p -> passesSinceFilter(params, p.getUpdatedAt()))
                .forEach(p -> addEntry(bundle, medicationRequestMapper.toFhir(p)));
        }

        bundle.setTotal(bundle.getEntry().size());

        if (hasMore[0]) {
            // FHIR R4 continuation idiom: relative "next" link with
            // the cursor advanced by 1. The provider is responsible
            // for prepending the absolute base URL; we ship the
            // relative shape so the runbook + tests can pin the
            // exact form.
            int nextCursor = pageOffset + 1;
            bundle.addLink()
                .setRelation("next")
                .setUrl(nextLink(patientId, params, nextCursor));
        }

        emitAudit(patient, describe(patientId, params, bundle.getTotal()));
        return bundle;
    }

    private boolean passesSinceFilter(PatientEverythingParams params, java.time.LocalDateTime updatedAt) {
        if (params.since() == null) return true;
        Instant resolved = updatedAt == null ? null : updatedAt.toInstant(ZoneOffset.UTC);
        return params.afterSince(resolved);
    }

    /**
     * Spring Data's {@link org.springframework.data.domain.Page} carries
     * {@code hasNext()} directly — that's the cleanest indicator that the
     * underlying query has more rows than what this page returned, and it
     * doesn't false-fire when a section happens to land exactly on the
     * page boundary with zero remaining rows.
     */
    private static boolean sectionHasMore(Object pageOrList) {
        if (pageOrList instanceof org.springframework.data.domain.Page<?> page) {
            return page.hasNext();
        }
        return false;
    }

    private static String nextLink(UUID patientId, PatientEverythingParams params, int nextCursor) {
        StringBuilder sb = new StringBuilder("Patient/").append(patientId).append("/$everything?");
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
        StringBuilder sb = new StringBuilder("FHIR Patient/")
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

    private static ResourceNotFoundException notFound(String message, OperationOutcome.IssueType type) {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(type)
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
            log.warn("audit emission failed for FHIR Patient/{}/$everything: {}",
                patient.getId(), ex.toString());
        }
    }
}
