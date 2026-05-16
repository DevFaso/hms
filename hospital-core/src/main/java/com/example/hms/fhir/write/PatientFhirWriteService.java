package com.example.hms.fhir.write;

import ca.uhn.fhir.rest.server.exceptions.MethodNotAllowedException;
import ca.uhn.fhir.rest.server.exceptions.PreconditionFailedException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.fhir.FhirWriteProperties;
import com.example.hms.fhir.mapper.PatientFhirMapper;
import com.example.hms.fhir.mapper.PatientFhirMapper.MrnIdentifier;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.AuditEventLogService;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Foundation-pass FHIR R4 write service for {@code Patient}
 * (roadmap row 20, v1.1 / Backend).
 *
 * <p>Two narrow operations:
 * <ul>
 *   <li>{@link #update(UUID, org.hl7.fhir.r4.model.Patient)} — PUT path.
 *       Applies the FHIR-mutable subset of fields
 *       ({@link PatientFhirMapper#applyFhirUpdates}) onto an existing
 *       entity and persists.</li>
 *   <li>{@link #conditionalCreate(String, org.hl7.fhir.r4.model.Patient)}
 *       — POST + {@code If-None-Exist} path. <strong>Never
 *       auto-provisions a new Patient.</strong> Looks up by active
 *       MRN: 1 match → 200 with existing resource; 0 matches → 404
 *       OperationOutcome (per empi-identity skill); &gt;1 matches →
 *       412 (kept unreachable by the V101 partial unique index).</li>
 * </ul>
 *
 * <p>Feature-flagged via {@link FhirWriteProperties#isEnabled()};
 * disabled state surfaces as {@code 405 Method Not Allowed} from the
 * provider.
 */
@Service
public class PatientFhirWriteService {

    private static final Logger log = LoggerFactory.getLogger(PatientFhirWriteService.class);
    private static final String OPERATION_OUTCOME_ENTITY = "Patient";

    private final FhirWriteProperties writeProperties;
    private final PatientFhirMapper patientMapper;
    private final PatientRepository patientRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final AuditEventLogService auditEventLogService;

    public PatientFhirWriteService(
        FhirWriteProperties writeProperties,
        PatientFhirMapper patientMapper,
        PatientRepository patientRepository,
        PatientHospitalRegistrationRepository registrationRepository,
        AuditEventLogService auditEventLogService
    ) {
        this.writeProperties = writeProperties;
        this.patientMapper = patientMapper;
        this.patientRepository = patientRepository;
        this.registrationRepository = registrationRepository;
        this.auditEventLogService = auditEventLogService;
    }

    public boolean isEnabled() {
        return writeProperties.isEnabled();
    }

    /**
     * PUT /Patient/{id}. Applies the FHIR-mutable subset to an existing
     * entity. Caller is the provider — exceptions propagate to HAPI's
     * exception handler which renders the matching HTTP status.
     */
    @Transactional
    public Patient update(UUID patientId, org.hl7.fhir.r4.model.Patient fhirIn) {
        ensureEnabled();
        Patient existing = patientRepository.findById(patientId)
            .orElseThrow(() -> notFoundWith(
                "Patient/" + patientId + " not found",
                OperationOutcome.IssueType.NOTFOUND
            ));
        patientMapper.applyFhirUpdates(existing, fhirIn);
        Patient saved = patientRepository.save(existing);
        emitAudit(AuditEventType.PATIENT_UPDATE, saved,
            "FHIR PUT applied contact/address updates to Patient/" + saved.getId());
        return saved;
    }

    /**
     * POST /Patient with {@code If-None-Exist}. Returns the existing
     * resource on a single-MRN match; never creates.
     *
     * <p>The {@code ifNoneExistRaw} string is the raw HAPI conditional
     * URL — e.g. {@code identifier=urn:hms:hospital:<uuid>:mrn|MRN-0042}.
     * Only the {@code identifier} parameter is honored; any other
     * search parameter is rejected as 422.
     */
    @Transactional(readOnly = true)
    public Patient conditionalCreate(String ifNoneExistRaw, org.hl7.fhir.r4.model.Patient fhirIn) {
        ensureEnabled();
        if (ifNoneExistRaw == null || ifNoneExistRaw.isBlank()) {
            throw unprocessable(
                "POST /Patient requires an If-None-Exist header — auto-provisioning is disabled.",
                OperationOutcome.IssueType.BUSINESSRULE
            );
        }
        String identifierToken = extractIdentifierToken(ifNoneExistRaw);
        if (identifierToken == null) {
            throw unprocessable(
                "If-None-Exist must contain an identifier=<system>|<mrn> clause; no other search parameters are supported.",
                OperationOutcome.IssueType.NOTSUPPORTED
            );
        }
        MrnIdentifier mrn = patientMapper.parseMrnSearchToken(identifierToken).orElseThrow(() ->
            unprocessable(
                "Identifier token must use system 'urn:hms:hospital:<hospitalId>:mrn'.",
                OperationOutcome.IssueType.NOTSUPPORTED
            )
        );

        List<PatientHospitalRegistration> matches = registrationRepository
            .findActiveByHospitalIdAndIdentifier(mrn.hospitalId(), mrn.mrn());

        long activeMrnMatches = matches.stream()
            .filter(r -> r != null && r.getMrn() != null
                && r.getMrn().equalsIgnoreCase(mrn.mrn()))
            .count();

        if (activeMrnMatches == 0) {
            throw notFoundWith(
                "No active Patient registered with MRN '" + mrn.mrn() + "' at hospital "
                    + mrn.hospitalId() + ".",
                OperationOutcome.IssueType.NOTFOUND
            );
        }
        if (activeMrnMatches > 1) {
            throw preconditionFailed(
                "Multiple active patients matched MRN '" + mrn.mrn() + "' at hospital "
                    + mrn.hospitalId() + " — resolve via EMPI merge.",
                OperationOutcome.IssueType.MULTIPLEMATCHES
            );
        }

        Patient resolved = matches.stream()
            .filter(r -> r != null && r.getMrn() != null
                && r.getMrn().equalsIgnoreCase(mrn.mrn()))
            .map(PatientHospitalRegistration::getPatient)
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElseThrow(() -> notFoundWith(
                "MRN row resolved but patient association is missing — registration row is corrupt.",
                OperationOutcome.IssueType.EXCEPTION
            ));

        emitAudit(AuditEventType.PATIENT_ACCESS, resolved,
            "FHIR conditional-create matched MRN " + mrn.mrn() + " — returned existing Patient/"
                + resolved.getId());
        return resolved;
    }

    private void ensureEnabled() {
        if (!writeProperties.isEnabled()) {
            throw new MethodNotAllowedException(
                "FHIR write API is disabled — set app.fhir.write.enabled=true to opt in."
            );
        }
    }

    private static String extractIdentifierToken(String ifNoneExist) {
        String trimmed = ifNoneExist.trim();
        if (trimmed.startsWith("?")) trimmed = trimmed.substring(1);
        String[] params = trimmed.split("&");
        for (String p : params) {
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            String key = p.substring(0, eq).trim();
            String val = p.substring(eq + 1).trim();
            if ("identifier".equalsIgnoreCase(key)) {
                return urlDecode(val);
            }
        }
        return null;
    }

    private static String urlDecode(String raw) {
        try {
            return java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (RuntimeException ex) {
            return raw;
        }
    }

    private static UnprocessableEntityException unprocessable(String message, OperationOutcome.IssueType type) {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(type)
            .setDiagnostics(message);
        return new UnprocessableEntityException(message, outcome);
    }

    private static ResourceNotFoundException notFoundWith(String message, OperationOutcome.IssueType type) {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(type)
            .setDiagnostics(message);
        ResourceNotFoundException ex = new ResourceNotFoundException(message, outcome);
        return ex;
    }

    private static PreconditionFailedException preconditionFailed(String message, OperationOutcome.IssueType type) {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(type)
            .setDiagnostics(message);
        return new PreconditionFailedException(message, outcome);
    }

    private void emitAudit(AuditEventType eventType, Patient patient, String description) {
        try {
            AuditEventRequestDTO request = AuditEventRequestDTO.builder()
                .eventType(eventType)
                .status(AuditStatus.SUCCESS)
                .entityType(OPERATION_OUTCOME_ENTITY)
                .resourceId(patient.getId() == null ? null : patient.getId().toString())
                .eventDescription(description)
                .build();
            auditEventLogService.logEvent(request);
        } catch (RuntimeException ex) {
            log.warn("audit emission failed for FHIR Patient {} (id={}): {}",
                eventType, patient.getId(), ex.toString());
        }
    }
}
