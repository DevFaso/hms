package com.example.hms.fhir.provider;

import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.param.DateParam;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.fhir.mapper.PatientFhirMapper;
import com.example.hms.model.Patient;
import com.example.hms.repository.PatientRepository;
import org.hl7.fhir.r4.model.IdType;
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

    public PatientFhirResourceProvider(PatientRepository patientRepository, PatientFhirMapper patientMapper) {
        this.patientRepository = patientRepository;
        this.patientMapper = patientMapper;
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
