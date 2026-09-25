package com.example.hms.fhir.write;

import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
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
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
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
 * <p>Cross-tenant: both operations are anchored on the caller's active
 * hospital (no scope → 403). PUT refuses a patient not registered there, and
 * the conditional-create lookup refuses an identifier system naming any other
 * hospital, each with exactly the 404 a nonexistent patient or MRN gets — an
 * unknown id and another tenant's id are indistinguishable (the rule HL7
 * settled in #715/#738).
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
     *
     * <p>Tenant gate: the patient must be registered at the caller's active
     * hospital. {@code PatientRepository.findById} alone is not that gate —
     * {@code TenantAwareJpaRepository} scopes it to every hospital the caller
     * is PERMITTED at (all of a multi-hospital user's assignments, every
     * hospital of a permitted organisation) and not at all for a super-admin,
     * so a writer pinned to hospital A could overwrite the contact and address
     * of a patient registered only at B, and read the whole resource back.
     *
     * <p>No oracle: an id that exists nowhere and an id registered only at
     * another hospital get the same 404 with the same body, and cost the same
     * single registration query, so a writer cannot sort candidate ids into
     * "real somewhere else" and "not real".
     */
    @Transactional
    public Patient update(UUID patientId, org.hl7.fhir.r4.model.Patient fhirIn) {
        ensureEnabled();
        UUID hospitalId = requireHospitalScope();
        Patient existing = findRegisteredAt(patientId, hospitalId)
            .orElseThrow(() -> patientNotFound(patientId));
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
        UUID callerHospitalId = requireHospitalScope();
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

        // The hospital in the identifier system comes from the REQUEST. Only the
        // caller's own active hospital may be searched: another hospital's MRN
        // space answers exactly like an MRN that matches nothing, never with the
        // other hospital's patient.
        List<PatientHospitalRegistration> matches = callerHospitalId.equals(mrn.hospitalId())
            ? registrationRepository.findActiveByHospitalIdAndIdentifier(mrn.hospitalId(), mrn.mrn())
            : List.of();

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

        // Ids only in the audit description: an MRN is a patient identifier.
        emitAudit(AuditEventType.PATIENT_ACCESS, resolved,
            "FHIR conditional-create matched an active MRN — returned existing Patient/"
                + resolved.getId());
        return resolved;
    }

    /**
     * The caller's active hospital, from the authenticated principal's
     * {@code HospitalContext} (an {@code X-Hospital-Id} pin is honoured only
     * inside the principal's permitted scope). No scope is refused up front as
     * 403, like the Encounter and Observation write paths: "pin a hospital"
     * reveals nothing about any identifier.
     */
    private static UUID requireHospitalScope() {
        UUID hospitalId = HospitalContextHolder.getContextOrEmpty().getActiveHospitalId();
        if (hospitalId == null) {
            OperationOutcome outcome = new OperationOutcome();
            outcome.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setCode(OperationOutcome.IssueType.FORBIDDEN)
                .setDiagnostics("FHIR Patient writes require an active hospital scope; supply "
                    + "X-Hospital-Id or authenticate as a hospital-scoped user.");
            throw new ForbiddenOperationException("An active hospital scope is required.", outcome);
        }
        return hospitalId;
    }

    /**
     * The patient, only when registered at {@code hospitalId}. The registration
     * is asked first so that a missing id and another hospital's id cost the
     * same one query and come back the same empty.
     */
    private Optional<Patient> findRegisteredAt(UUID patientId, UUID hospitalId) {
        if (!registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)) {
            return Optional.empty();
        }
        return patientRepository.findById(patientId);
    }

    /**
     * Deliberately one answer for "no such patient" and "registered at another
     * hospital" — the same wording {@code Patient/{id}/$everything} uses.
     */
    private static ResourceNotFoundException patientNotFound(UUID patientId) {
        return notFoundWith(
            "Patient/" + patientId + " is not registered at your active hospital. If you expect "
                + "this record, switch your hospital scope to one where the patient is registered.",
            OperationOutcome.IssueType.NOTFOUND
        );
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
