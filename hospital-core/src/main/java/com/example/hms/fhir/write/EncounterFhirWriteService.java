package com.example.hms.fhir.write;

import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.MethodNotAllowedException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.fhir.FhirWriteProperties;
import com.example.hms.fhir.mapper.EncounterFhirMapper;
import com.example.hms.model.Encounter;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.EncounterRepository;
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
 * FHIR R4 write service for {@code Encounter} (roadmap row 20 follow-on,
 * v1.1 / Backend / Interop FHIR).
 *
 * <p>Single narrow operation:
 * <ul>
 *   <li>{@link #update(UUID, org.hl7.fhir.r4.model.Encounter)} — PUT path.
 *       Applies the FHIR-mutable subset of fields
 *       ({@link EncounterFhirMapper#applyFhirUpdates}) onto an existing
 *       entity and persists. Honored fields are {@code period.end} (only
 *       when {@code checkoutTimestamp} is currently null) and
 *       {@code reasonCode[0].text} (only when {@code chiefComplaint} is
 *       currently null/blank).</li>
 * </ul>
 *
 * <p>POST is intentionally NOT implemented — encounter provisioning has
 * mandatory invariants (staff @ hospital, assignment @ hospital,
 * appointment match) enforced by {@link Encounter#validate} that an
 * inbound FHIR sender cannot reliably satisfy. New encounters flow
 * through the clinical workflow path which carries the deeper audit
 * trail.
 *
 * <p>Cross-tenant: the active hospital id is read from
 * {@link HospitalContextHolder}; the encounter is fetched via
 * {@link EncounterRepository#findByIdAndHospital_Id} so a caller in
 * tenant A cannot mutate tenant B's encounter even if they hold the
 * UUID. A missing active hospital is rejected as 403, mirroring the
 * "no auto-provisioning without scope" stance the Patient write path
 * inherits from the empi-identity skill.
 *
 * <p>Feature-flagged via {@link FhirWriteProperties#isEnabled()};
 * disabled state surfaces as {@code 405 Method Not Allowed} from the
 * provider.
 */
@Service
public class EncounterFhirWriteService {

    private static final Logger log = LoggerFactory.getLogger(EncounterFhirWriteService.class);
    private static final String AUDIT_ENTITY_TYPE = "ENCOUNTER";

    private final FhirWriteProperties writeProperties;
    private final EncounterFhirMapper encounterMapper;
    private final EncounterRepository encounterRepository;
    private final AuditEventLogService auditEventLogService;

    public EncounterFhirWriteService(
        FhirWriteProperties writeProperties,
        EncounterFhirMapper encounterMapper,
        EncounterRepository encounterRepository,
        AuditEventLogService auditEventLogService
    ) {
        this.writeProperties = writeProperties;
        this.encounterMapper = encounterMapper;
        this.encounterRepository = encounterRepository;
        this.auditEventLogService = auditEventLogService;
    }

    public boolean isEnabled() {
        return writeProperties.isEnabled();
    }

    /**
     * PUT /Encounter/{id}. Applies the FHIR-mutable subset to an
     * existing entity. Caller is the provider — exceptions propagate to
     * HAPI's exception handler which renders the matching HTTP status.
     */
    @Transactional
    public Encounter update(UUID encounterId, org.hl7.fhir.r4.model.Encounter fhirIn) {
        ensureEnabled();
        UUID hospitalId = HospitalContextHolder.getContextOrEmpty().getActiveHospitalId();
        if (hospitalId == null) {
            throw forbidden(
                "FHIR PUT /Encounter requires an active hospital scope; supply X-Hospital-Id "
                    + "or authenticate as a hospital-scoped user."
            );
        }
        Encounter existing = encounterRepository.findByIdAndHospital_Id(encounterId, hospitalId)
            .orElseThrow(() -> notFoundWith(
                "Encounter/" + encounterId + " not found at the active hospital scope.",
                OperationOutcome.IssueType.NOTFOUND
            ));

        // Defence-in-depth: the repository scope already filters by hospital,
        // but if the loaded entity disagrees with the context (which would
        // mean a misconfigured query alias) we refuse rather than silently
        // accept the write.
        if (existing.getHospital() == null
            || !Objects.equals(existing.getHospital().getId(), hospitalId)) {
            throw forbidden(
                "Encounter " + encounterId + " hospital scope does not match the active hospital."
            );
        }

        encounterMapper.applyFhirUpdates(existing, fhirIn);
        Encounter saved = encounterRepository.save(existing);
        emitAudit(saved,
            "FHIR PUT applied chief-complaint / checkout updates to Encounter/" + saved.getId());
        return saved;
    }

    private void ensureEnabled() {
        if (!writeProperties.isEnabled()) {
            throw new MethodNotAllowedException(
                "FHIR write API is disabled — set app.fhir.write.enabled=true to opt in."
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

    private static ForbiddenOperationException forbidden(String message) {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(OperationOutcome.IssueType.FORBIDDEN)
            .setDiagnostics(message);
        return new ForbiddenOperationException(message, outcome);
    }

    private void emitAudit(Encounter encounter, String description) {
        try {
            AuditEventRequestDTO request = AuditEventRequestDTO.builder()
                .eventType(AuditEventType.ENCOUNTER_UPDATE)
                .status(AuditStatus.SUCCESS)
                .entityType(AUDIT_ENTITY_TYPE)
                .resourceId(encounter.getId() == null ? null : encounter.getId().toString())
                .eventDescription(description)
                .build();
            auditEventLogService.logEvent(request);
        } catch (RuntimeException ex) {
            log.warn("audit emission failed for FHIR Encounter update (id={}): {}",
                encounter.getId(), ex.toString());
        }
    }
}
