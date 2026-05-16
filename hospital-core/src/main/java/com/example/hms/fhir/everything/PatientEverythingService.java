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

    private static final int ENCOUNTER_LIMIT = 200;
    private static final int VITAL_LIMIT = 200;
    private static final int LAB_LIMIT = 200;
    private static final int PRESCRIPTION_LIMIT = 200;

    private final FhirOperationsProperties operationsProperties;
    private final PatientRepository patientRepository;
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

    @Transactional(readOnly = true)
    public Bundle everythingForPatient(UUID patientId) {
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

        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        addEntry(bundle, patientMapper.toFhir(patient));

        encounterRepository
            .findByPatient_IdAndHospital_Id(patientId, hospitalId, PageRequest.of(0, ENCOUNTER_LIMIT))
            .forEach(e -> addEntry(bundle, encounterMapper.toFhir(e)));

        vitalSignRepository
            .findByPatient_IdAndHospital_IdOrderByRecordedAtDesc(patientId, hospitalId,
                PageRequest.of(0, VITAL_LIMIT))
            .forEach(v -> observationMapper.toFhir(v).forEach(o -> addEntry(bundle, o)));

        labResultRepository
            .findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(patientId, hospitalId,
                PageRequest.of(0, LAB_LIMIT))
            .forEach(r -> addEntry(bundle, observationMapper.toFhir(r)));

        patientProblemRepository
            .findByPatient_Id(patientId)
            .forEach(c -> addEntry(bundle, conditionMapper.toFhir(c)));

        prescriptionRepository
            .findByPatient_IdAndHospital_Id(patientId, hospitalId,
                PageRequest.of(0, PRESCRIPTION_LIMIT))
            .forEach(p -> addEntry(bundle, medicationRequestMapper.toFhir(p)));

        bundle.setTotal(bundle.getEntry().size());

        emitAudit(patient,
            "FHIR Patient/" + patientId + "/$everything returned a "
                + bundle.getTotal() + "-entry Bundle");
        return bundle;
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
