package com.example.hms.fhir.provider;

import ca.uhn.fhir.rest.annotation.ConditionalUrlParam;
import ca.uhn.fhir.rest.annotation.Create;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
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
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.MethodNotAllowedException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import com.example.hms.fhir.FhirTenantBoundary;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.fhir.everything.PatientEverythingParams;
import com.example.hms.fhir.everything.PatientEverythingService;
import com.example.hms.fhir.read.PatientFhirReadService;
import com.example.hms.fhir.write.PatientFhirWriteService;
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
 * <p>Every resource this provider returns is mapped by
 * {@link PatientFhirReadService} or {@link PatientFhirWriteService}, inside
 * their transaction: the mapper walks the LAZY
 * {@code Patient.hospitalRegistrations}, open-in-view is off, and the HAPI
 * servlet opens no transaction, so mapping an entity here was a 500 for every
 * caller. Nothing in this class touches an entity.
 *
 * <p>Tenancy, stated as it is rather than as it was once described. Read and
 * {@code _id} search go through {@link com.example.hms.repository.PatientRepository#findById(Object)},
 * which {@code TenantAwareJpaRepository} filters with
 * {@code TenantScopeSpecification}: a patient is found when registered at ANY
 * hospital the caller is permitted at (every assignment of a multi-hospital
 * user, every hospital of a permitted organisation), and a super-admin is not
 * filtered at all. That is wider than the active hospital the other FHIR
 * providers anchor on. The {@code tenantContext} bean filters only the
 * name/identifier search query. Writes do NOT rely on either: PUT and the
 * conditional POST are gated on a registration at the active hospital inside
 * {@link PatientFhirWriteService}.
 *
 * <p>Search is intentionally narrow at this stage — it covers the parameters
 * downstream consumers (OpenMRS, DHIS2 Tracker, OpenHIE) require for patient
 * matching: identifier, name, given, family, birthdate, phone, email, active.
 * Sort is fixed (family, given) until the search story is filled out.
 */
@Component
public class PatientFhirResourceProvider implements IResourceProvider {

    private static final int DEFAULT_PAGE_SIZE = 50;

    private final PatientFhirReadService readService;
    private final PatientFhirWriteService writeService;
    private final PatientEverythingService everythingService;
    private final FhirTenantBoundary tenantBoundary;

    public PatientFhirResourceProvider(
        PatientFhirReadService readService,
        PatientFhirWriteService writeService,
        PatientEverythingService everythingService,
        FhirTenantBoundary tenantBoundary
    ) {
        this.tenantBoundary = tenantBoundary;
        this.readService = readService;
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
        return readService.read(uuid)
            .orElseThrow(() -> new ResourceNotFoundException(id));
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
        UUID boundHospital = FhirTenantBoundary.boundHospital(HospitalContextHolder.getContextOrEmpty());
        if (idParam != null && idParam.getValue() != null) {
            UUID uuid = tryParseUuid(idParam.getValue());
            if (uuid == null) return Collections.emptyList();
            // Asked BEFORE loading: the tenant boundary filters the bundle on
            // the way out, but a patient registered elsewhere in the caller's
            // organisation used to fail in the mapper (500) before it got
            // there, while an unknown id answered an empty bundle.
            if (!tenantBoundary.isVisible("Patient", uuid.toString(), boundHospital)) {
                return Collections.emptyList();
            }
            return readService.read(uuid)
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
        // The page is capped, so it must be a page of the hospital the request
        // is bound to (the interceptor has already refused a request with none): capping across every permitted hospital and then letting
        // the tenant boundary drop the others silently loses the bound
        // hospital's own matches past the cap.
        return readService.search(
            new PatientFhirReadService.SearchCriteria(
                mrn,
                namePattern,
                normalizeDob(dob),
                phonePattern,
                emailPattern,
                boundHospital,
                activeFlag),
            PageRequest.of(0, DEFAULT_PAGE_SIZE, sort)
        );
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
     *
     * <p>The tenant gate is in {@link PatientFhirWriteService#update}: a
     * patient not registered at the caller's active hospital answers the same
     * 404 as one that does not exist.
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
        org.hl7.fhir.r4.model.Patient saved = writeService.update(uuid, resource);
        return new MethodOutcome()
            .setId(new IdType("Patient", saved.getIdElement().getIdPart()))
            .setResource(saved);
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
        org.hl7.fhir.r4.model.Patient resolved = writeService.conditionalCreate(conditionalUrl, resource);
        return new MethodOutcome()
            .setId(new IdType("Patient", resolved.getIdElement().getIdPart()))
            .setResource(resolved)
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
    public Bundle patientEverything(
        @IdParam IdType id,
        @OperationParam(name = "_since", min = 0, max = 1) org.hl7.fhir.r4.model.InstantType since,
        @OperationParam(name = "_type", min = 0, max = 1) org.hl7.fhir.r4.model.StringType type,
        @OperationParam(name = "_count", min = 0, max = 1) org.hl7.fhir.r4.model.IntegerType count,
        @OperationParam(name = "_page", min = 0, max = 1) org.hl7.fhir.r4.model.IntegerType page
    ) {
        UUID uuid = parseUuid(id);
        PatientEverythingParams params = parseEverythingParams(since, type, count, page);
        return everythingService.everythingForPatient(uuid, params);
    }

    /**
     * Parses the four $everything search-control parameters into the
     * service-layer record. Malformed inputs (negative {@code _count},
     * negative {@code _page}) surface as
     * {@code 400 + OperationOutcome(VALUE)} via
     * {@link InvalidRequestException} — same shape the bulk-data spec
     * requires for malformed {@code _since} / {@code _outputFormat}.
     *
     * <p>A blank or whitespace-only {@code _type} is treated as absent
     * (no type filter) per
     * {@link PatientEverythingParams#parseTypeList(String)} —
     * callers can omit the parameter or send "" with the same effect.
     */
    private static PatientEverythingParams parseEverythingParams(
        org.hl7.fhir.r4.model.InstantType since,
        org.hl7.fhir.r4.model.StringType type,
        org.hl7.fhir.r4.model.IntegerType count,
        org.hl7.fhir.r4.model.IntegerType page
    ) {
        try {
            java.time.Instant sinceInstant = (since == null || since.getValue() == null)
                ? null
                : since.getValue().toInstant();
            var types = (type == null || type.getValue() == null)
                ? java.util.Set.<String>of()
                : PatientEverythingParams.parseTypeList(type.getValue());
            Integer countValue = (count == null || count.getValue() == null)
                ? null
                : count.getValue();
            Integer pageValue = (page == null || page.getValue() == null)
                ? null
                : page.getValue();
            return PatientEverythingParams.of(sinceInstant, types, countValue, pageValue);
        } catch (IllegalArgumentException ex) {
            OperationOutcome outcome = new OperationOutcome();
            outcome.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setCode(OperationOutcome.IssueType.VALUE)
                .setDiagnostics(ex.getMessage());
            throw new InvalidRequestException(ex.getMessage(), outcome);
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
