package com.example.hms.fhir.provider;

import ca.uhn.fhir.rest.annotation.ConditionalUrlParam;
import ca.uhn.fhir.rest.annotation.Create;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.annotation.Update;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.param.DateParam;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.MethodNotAllowedException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import com.example.hms.fhir.everything.PatientEverythingService;
import com.example.hms.fhir.mapper.PatientFhirMapper;
import com.example.hms.fhir.write.PatientFhirWriteService;
import com.example.hms.model.Patient;
import com.example.hms.repository.PatientRepository;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * FHIR R4 resource provider for {@code Patient}.
 *
 * <p>Read is tenant-scoped through {@link PatientRepository#findById(Object)} which
 * already applies hospital-context filters via the {@code tenantContext} bean.
 *
 * <p>Search is intentionally narrow at this stage — it covers the parameters
 * downstream consumers (OpenMRS, DHIS2 Tracker, OpenHIE) require for patient
 * matching: identifier, name, given, family, birthdate, phone, email, active.
 * Sort is fixed (family, given) until the search story is filled out.
 */
@Component
public class PatientFhirResourceProvider implements IResourceProvider {

    private static final int DEFAULT_PAGE_SIZE = 50;

    private final PatientRepository patientRepository;
    private final PatientFhirMapper patientMapper;
    private final PatientFhirWriteService writeService;
    private final PatientEverythingService everythingService;

    public PatientFhirResourceProvider(
        PatientRepository patientRepository,
        PatientFhirMapper patientMapper,
        PatientFhirWriteService writeService,
        PatientEverythingService everythingService
    ) {
        this.patientRepository = patientRepository;
        this.patientMapper = patientMapper;
        this.writeService = writeService;
        this.everythingService = everythingService;
    }

    @Override
    public Class<org.hl7.fhir.r4.model.Patient> getResourceType() {
        return org.hl7.fhir.r4.model.Patient.class;
    }

    @Read
    public org.hl7.fhir.r4.model.Patient read(@IdParam IdType id) {
        UUID uuid = parseUuid(id);
        Patient entity = patientRepository.findById(uuid)
            .orElseThrow(() -> new ResourceNotFoundException(id));
        return patientMapper.toFhir(entity);
    }

    /**
     * Search supports the parameters that can be honored by the existing
     * {@code searchPatientsExtended} repository query without post-paginate
     * in-memory filtering. {@code given}, {@code family}, and {@code gender}
     * are intentionally not exposed yet — pushing them into the JPA query is
     * a P1 follow-up. Until then, callers should use the broader {@code name}
     * parameter (which matches first / last / concatenated name).
     */
    @Search
    public List<org.hl7.fhir.r4.model.Patient> search(
        @OptionalParam(name = "_id") TokenParam idParam,
        @OptionalParam(name = "identifier") TokenParam identifier,
        @OptionalParam(name = "name") StringParam name,
        @OptionalParam(name = "birthdate") DateParam birthdate,
        @OptionalParam(name = "phone") TokenParam phone,
        @OptionalParam(name = "email") TokenParam email,
        @OptionalParam(name = "active") TokenParam active
    ) {
        if (idParam != null && idParam.getValue() != null) {
            UUID uuid = tryParseUuid(idParam.getValue());
            if (uuid == null) return Collections.emptyList();
            return patientRepository.findById(uuid)
                .map(patientMapper::toFhir)
                .map(List::of)
                .orElseGet(Collections::emptyList);
        }

        String mrn = (identifier != null && identifier.getValue() != null) ? identifier.getValue() : null;
        String namePattern = stringPattern(name);
        String dob = (birthdate != null && birthdate.getValue() != null)
            ? birthdate.getValueAsString()
            : null;
        String phonePattern = phone != null && phone.getValue() != null
            ? "%" + phone.getValue() + "%"
            : null;
        String emailPattern = email != null && email.getValue() != null
            ? "%" + email.getValue().toLowerCase() + "%"
            : null;
        Boolean activeFlag = active != null && active.getValue() != null
            ? Boolean.parseBoolean(active.getValue())
            : null;

        var sort = Sort.by(Sort.Order.asc("lastName"), Sort.Order.asc("firstName"));
        var page = patientRepository.searchPatientsExtended(
            mrn,
            namePattern,
            normalizeDob(dob),
            phonePattern,
            emailPattern,
            null,
            activeFlag,
            PageRequest.of(0, DEFAULT_PAGE_SIZE, sort)
        );

        return page.stream()
            .map(patientMapper::toFhir)
            .toList();
    }

    /**
     * PUT /Patient/{id}. Updates the FHIR-mutable subset (address +
     * telecom + active flag) of an existing patient. Identity columns
     * (name / DOB / gender) are intentionally not honored — those flow
     * through the registration admin path.
     *
     * <p>Feature-flagged: when {@code app.fhir.write.enabled=false}
     * (default) the write service throws {@code MethodNotAllowedException}
     * → 405.
     */
    @Update
    public MethodOutcome update(
        @IdParam IdType id,
        @ResourceParam org.hl7.fhir.r4.model.Patient resource
    ) {
        // Flag-first ordering (PR #343 Copilot review): when the write
        // API is disabled, return 405 BEFORE any request-shape
        // validation runs. Without this, flag-off requests with a
        // mismatched body id would return 422 — contradicting the
        // documented flag-off contract.
        if (!writeService.isEnabled()) {
            throw new MethodNotAllowedException(
                "FHIR write API is disabled — set app.fhir.write.enabled=true to opt in."
            );
        }
        UUID uuid = parseUuid(id);
        if (resource == null) {
            throw unprocessable(
                "PUT /Patient/{id} requires a Patient resource body.",
                OperationOutcome.IssueType.STRUCTURE
            );
        }
        if (resource.getIdElement() != null && resource.getIdElement().getIdPart() != null
            && !resource.getIdElement().getIdPart().isBlank()
            && !resource.getIdElement().getIdPart().equals(uuid.toString())) {
            throw unprocessable(
                "Resource.id does not match the URL id; refusing to honor PUT against a mismatched id.",
                OperationOutcome.IssueType.BUSINESSRULE
            );
        }
        Patient saved = writeService.update(uuid, resource);
        return new MethodOutcome()
            .setId(new IdType("Patient", saved.getId().toString()))
            .setResource(patientMapper.toFhir(saved));
    }

    /**
     * POST /Patient. By contract the only honored variant carries an
     * {@code If-None-Exist=identifier=<mrn-system>|<mrn>} header. Zero
     * matches → 404 (no auto-provisioning), one match → 200 with the
     * existing resource, multiple matches → 412.
     */
    @Create
    public MethodOutcome create(
        @ResourceParam org.hl7.fhir.r4.model.Patient resource,
        @ConditionalUrlParam String conditionalUrl
    ) {
        // Flag-first ordering: mirror the @Update fix. PR #343 Copilot
        // review noted the same gap on @Create.
        if (!writeService.isEnabled()) {
            throw new MethodNotAllowedException(
                "FHIR write API is disabled — set app.fhir.write.enabled=true to opt in."
            );
        }
        if (resource == null) {
            throw unprocessable(
                "POST /Patient requires a Patient resource body.",
                OperationOutcome.IssueType.STRUCTURE
            );
        }
        Patient resolved = writeService.conditionalCreate(conditionalUrl, resource);
        return new MethodOutcome()
            .setId(new IdType("Patient", resolved.getId().toString()))
            .setResource(patientMapper.toFhir(resolved))
            .setCreated(false);
    }

    /**
     * Patient compartment {@code $everything} (roadmap row 22).
     * Returns a {@link Bundle} containing the requested Patient and the
     * resources in its compartment (Encounters, Observations,
     * Conditions, MedicationRequests). Feature-flagged via
     * {@code app.fhir.operations.everything.enabled} — flag-off
     * surfaces as 405 from {@link PatientEverythingService#everythingForPatient}.
     */
    @Operation(name = "$everything", idempotent = true, type = org.hl7.fhir.r4.model.Patient.class)
    public Bundle patientEverything(@IdParam IdType id) {
        UUID uuid = parseUuid(id);
        return everythingService.everythingForPatient(uuid);
    }

    private static UnprocessableEntityException unprocessable(String message, OperationOutcome.IssueType type) {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(type)
            .setDiagnostics(message);
        return new UnprocessableEntityException(message, outcome);
    }

    private static String stringPattern(StringParam param) {
        if (param == null || param.getValue() == null || param.getValue().isBlank()) return null;
        return "%" + param.getValue().trim().toLowerCase() + "%";
    }

    private static String normalizeDob(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return DateTimeFormatter.ISO_LOCAL_DATE.format(java.time.LocalDate.parse(raw.substring(0, 10)));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static UUID parseUuid(IdType id) {
        if (id == null || id.getIdPart() == null) {
            throw new ResourceNotFoundException(id);
        }
        return tryParseUuidOrThrow(id.getIdPart(), id);
    }

    private static UUID tryParseUuidOrThrow(String raw, IdType id) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new ResourceNotFoundException(id);
        }
    }

    private static UUID tryParseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

}
