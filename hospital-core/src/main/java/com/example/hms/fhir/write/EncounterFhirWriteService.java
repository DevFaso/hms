package com.example.hms.fhir.write;

import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.MethodNotAllowedException;
import ca.uhn.fhir.rest.server.exceptions.PreconditionFailedException;
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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
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
     * Foundation entry-point — preserved for callers that haven't
     * migrated to the version-aware overload. Equivalent to
     * {@link #update(UUID, org.hl7.fhir.r4.model.Encounter, String)}
     * with no If-Match precondition (last-write-wins).
     */
    @Transactional
    public Encounter update(UUID encounterId, org.hl7.fhir.r4.model.Encounter fhirIn) {
        return doUpdate(encounterId, fhirIn, null);
    }

    /**
     * Row-20 follow-on: PUT /Encounter/{id} with optional
     * {@code If-Match} optimistic-concurrency precondition. When
     * {@code ifMatchHeader} is supplied, the encoded version
     * (rendered by {@link #toVersionId(LocalDateTime)} from the
     * entity's {@code updatedAt}) MUST match the encounter's current
     * version; otherwise the call rejects as
     * {@link PreconditionFailedException} (412 Precondition Failed)
     * with a FHIR {@code OperationOutcome(CONFLICT)} body.
     *
     * <p>Accepted header forms: {@code W/"<digits>"} (weak ETag, the
     * shape HAPI renders by default) and {@code "<digits>"} (strong
     * ETag, accepted for client convenience). A blank / null header
     * skips the precondition entirely — the same behaviour as the
     * no-arg overload, preserving the row-20-foundation contract for
     * callers that haven't opted into optimistic concurrency.
     */
    @Transactional
    public Encounter update(
        UUID encounterId,
        org.hl7.fhir.r4.model.Encounter fhirIn,
        String ifMatchHeader
    ) {
        return doUpdate(encounterId, fhirIn, ifMatchHeader);
    }

    /**
     * Single implementation shared by both public overloads. Kept
     * private so the two {@code @Transactional} entry-points each
     * go through Spring's proxy on external invocation without
     * self-calling each other (which would bypass the proxy and
     * defeat the transactional boundary — Sonar S2229).
     */
    private Encounter doUpdate(
        UUID encounterId,
        org.hl7.fhir.r4.model.Encounter fhirIn,
        String ifMatchHeader
    ) {
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

        // If-Match precondition (row-20 follow-on). Compare the caller's
        // version token to the entity's current updatedAt-derived
        // version BEFORE applying the mutation. The fast-fail prevents
        // a lost-update where two clients both PUT against the same
        // version and the second overwrites the first's changes.
        Optional<String> expected = parseIfMatch(ifMatchHeader);
        if (expected.isPresent()) {
            String actual = toVersionId(existing.getUpdatedAt());
            if (!expected.get().equals(actual)) {
                throw preconditionFailed(
                    "If-Match precondition failed for Encounter/" + encounterId
                        + " — expected version " + expected.get() + " but server has " + actual
                );
            }
        }

        encounterMapper.applyFhirUpdates(existing, fhirIn);
        Encounter saved = encounterRepository.save(existing);
        emitAudit(saved,
            "FHIR PUT applied chief-complaint / checkout updates to Encounter/" + saved.getId());
        return saved;
    }

    /**
     * Renders {@code updatedAt} as the version token used in both the
     * {@code ETag:} response header and the {@code If-Match:} request
     * header. Format: zero-padded epoch-millis. Monotonically
     * increasing per Hibernate's {@code @UpdateTimestamp}, so a
     * later version-id always represents a later state of the row.
     *
     * <p>Public so the provider + the mapper can compose the same
     * string for the response {@code meta.versionId} (HAPI then
     * renders the ETag header automatically from {@code meta.versionId}).
     */
    public static String toVersionId(LocalDateTime updatedAt) {
        if (updatedAt == null) {
            // Brand-new entity before its first @UpdateTimestamp fires.
            // Use 0 so the first PUT against a fresh row that submits
            // If-Match: W/"0" still works.
            return "0";
        }
        long epochMillis = updatedAt.toInstant(ZoneOffset.UTC).toEpochMilli();
        return Long.toString(epochMillis);
    }

    /**
     * Extract the version-id from an {@code If-Match} header. Accepts
     * both weak ({@code W/"123"}) and strong ({@code "123"}) ETag
     * shapes. Returns empty when the header is null / blank — the
     * caller skips the precondition in that case.
     */
    static Optional<String> parseIfMatch(String raw) {
        if (raw == null) return Optional.empty();
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return Optional.empty();
        if (trimmed.startsWith("W/")) {
            trimmed = trimmed.substring(2);
        }
        if (trimmed.length() >= 2
            && trimmed.charAt(0) == '"'
            && trimmed.charAt(trimmed.length() - 1) == '"') {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
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

    private static PreconditionFailedException preconditionFailed(String message) {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(OperationOutcome.IssueType.CONFLICT)
            .setDiagnostics(message);
        return new PreconditionFailedException(message, outcome);
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
