package com.example.hms.fhir.write;

import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.MethodNotAllowedException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.fhir.FhirWriteProperties;
import com.example.hms.fhir.mapper.ObservationFhirMapper;
import com.example.hms.model.LabResult;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * FHIR R4 write service for {@code Observation} (roadmap row 20
 * follow-on, v1.1 / Backend / Interop FHIR).
 *
 * <p>The Observation domain has a 1:N expansion on the read side
 * ({@link com.example.hms.model.PatientVitalSign} → up to seven
 * Observation resources, one per measured component). PUT against a
 * {@code vital-*} id has no natural single-row write target and the
 * row-20 deliverable text explicitly carves out vitals — those return
 * {@code 422 BUSINESSRULE}.
 *
 * <p>Lab results are 1:1: a single {@link LabResult} maps to a single
 * Observation with id {@code labresult-{uuid}}. The {@code labresult-*}
 * namespace is the only honored write target.
 *
 * <p><strong>Narrow honored subset.</strong> Only {@code note[0].text}
 * is applied to the existing entity — see
 * {@link ObservationFhirMapper#applyFhirLabResultUpdates} for the
 * append rules. {@code status}, {@code value}, {@code code} et al. are
 * not honored: release / sign / acknowledge transitions are
 * actor-stamped state-machine events and the FHIR PUT path has no
 * signer.
 *
 * <p>Cross-tenant: the active hospital id is read from
 * {@link HospitalContextHolder}; the lab result is fetched by id and
 * its parent {@code labOrder.hospital} must match. A missing active
 * hospital is rejected as 403.
 *
 * <p>Feature-flagged via {@link FhirWriteProperties#isEnabled()};
 * disabled state surfaces as {@code 405 Method Not Allowed} from the
 * provider.
 */
@Service
public class ObservationFhirWriteService {

    private static final Logger log = LoggerFactory.getLogger(ObservationFhirWriteService.class);
    private static final String LAB_RESULT_PREFIX = "labresult-";
    private static final String VITAL_PREFIX = "vital-";
    private static final String AUDIT_ENTITY_TYPE = "LAB_RESULT";

    private final FhirWriteProperties writeProperties;
    private final ObservationFhirMapper observationMapper;
    private final LabResultRepository labResultRepository;
    private final AuditEventLogService auditEventLogService;

    public ObservationFhirWriteService(
        FhirWriteProperties writeProperties,
        ObservationFhirMapper observationMapper,
        LabResultRepository labResultRepository,
        AuditEventLogService auditEventLogService
    ) {
        this.writeProperties = writeProperties;
        this.observationMapper = observationMapper;
        this.labResultRepository = labResultRepository;
        this.auditEventLogService = auditEventLogService;
    }

    public boolean isEnabled() {
        return writeProperties.isEnabled();
    }

    /**
     * PUT /Observation/{id}. Resolves the namespaced FHIR id back to a
     * LabResult and applies the FHIR-mutable subset. {@code vital-*}
     * ids are rejected with 422. Unknown namespaces are 404.
     */
    @Transactional
    public LabResult updateLabResult(String fhirIdPart, org.hl7.fhir.r4.model.Observation fhirIn) {
        ensureEnabled();
        if (fhirIdPart == null || fhirIdPart.isBlank()) {
            throw notFoundWith("Observation id is required.", OperationOutcome.IssueType.NOTFOUND);
        }
        if (fhirIdPart.startsWith(VITAL_PREFIX)) {
            throw unprocessable(
                "FHIR PUT against vital-signs Observations is not supported — the source row maps "
                    + "1:N to seven Observation components and has no single write target. "
                    + "Update vital signs through the clinical workflow path.",
                OperationOutcome.IssueType.BUSINESSRULE
            );
        }
        if (!fhirIdPart.startsWith(LAB_RESULT_PREFIX)) {
            throw notFoundWith(
                "Observation/" + fhirIdPart + " — only the labresult-{uuid} namespace is writable.",
                OperationOutcome.IssueType.NOTFOUND
            );
        }

        UUID labResultId = parseLabResultUuid(fhirIdPart);
        UUID hospitalId = HospitalContextHolder.getContextOrEmpty().getActiveHospitalId();
        if (hospitalId == null) {
            throw forbidden(
                "FHIR PUT /Observation requires an active hospital scope; supply X-Hospital-Id "
                    + "or authenticate as a hospital-scoped user."
            );
        }

        LabResult existing = labResultRepository.findById(labResultId)
            .orElseThrow(() -> notFoundWith(
                "Observation/" + fhirIdPart + " not found.",
                OperationOutcome.IssueType.NOTFOUND
            ));

        UUID resultHospitalId = existing.getLabOrder() == null || existing.getLabOrder().getHospital() == null
            ? null
            : existing.getLabOrder().getHospital().getId();
        if (resultHospitalId == null || !Objects.equals(resultHospitalId, hospitalId)) {
            throw forbidden(
                "LabResult " + labResultId + " does not belong to the active hospital scope."
            );
        }

        observationMapper.applyFhirLabResultUpdates(existing, fhirIn);
        LabResult saved = labResultRepository.save(existing);
        emitAudit(saved,
            "FHIR PUT appended note to LabResult/" + saved.getId());
        return saved;
    }

    private void ensureEnabled() {
        if (!writeProperties.isEnabled()) {
            throw new MethodNotAllowedException(
                "FHIR write API is disabled — set app.fhir.write.enabled=true to opt in."
            );
        }
    }

    private static UUID parseLabResultUuid(String fhirIdPart) {
        String raw = fhirIdPart.substring(LAB_RESULT_PREFIX.length());
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw notFoundWith(
                "Observation/" + fhirIdPart + " — id suffix is not a valid UUID.",
                OperationOutcome.IssueType.NOTFOUND
            );
        }
    }

    private static ResourceNotFoundException notFoundWith(String message, OperationOutcome.IssueType type) {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(type)
            .setDiagnostics(message);
        return new ResourceNotFoundException(message, outcome);
    }

    private static UnprocessableEntityException unprocessable(String message, OperationOutcome.IssueType type) {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(type)
            .setDiagnostics(message);
        return new UnprocessableEntityException(message, outcome);
    }

    private static ForbiddenOperationException forbidden(String message) {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(OperationOutcome.IssueType.FORBIDDEN)
            .setDiagnostics(message);
        return new ForbiddenOperationException(message, outcome);
    }

    private void emitAudit(LabResult result, String description) {
        try {
            AuditEventRequestDTO request = AuditEventRequestDTO.builder()
                .eventType(AuditEventType.LAB_RESULT_UPDATED)
                .status(AuditStatus.SUCCESS)
                .entityType(AUDIT_ENTITY_TYPE)
                .resourceId(result.getId() == null ? null : result.getId().toString())
                .eventDescription(description)
                .build();
            auditEventLogService.logEvent(request);
        } catch (RuntimeException ex) {
            log.warn("audit emission failed for FHIR Observation/labresult update (id={}): {}",
                result.getId(), ex.toString());
        }
    }
}
