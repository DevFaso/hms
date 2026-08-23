package com.example.hms.fhir.provider;

import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.annotation.Update;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenOrListParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.MethodNotAllowedException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import com.example.hms.fhir.mapper.ObservationFhirMapper;
import com.example.hms.fhir.write.ObservationFhirWriteService;
import com.example.hms.model.LabResult;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.security.context.HospitalContextHolder;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * FHIR R4 {@code Observation} provider sourced from two domains:
 * <ul>
 *   <li>{@link PatientVitalSignRepository} — vital-signs category</li>
 *   <li>{@link LabResultRepository} — laboratory category</li>
 * </ul>
 *
 * <p>Resource ids are namespaced ({@code vital-{rowId}-{component}}, {@code labresult-{rowId}})
 * so a single FHIR id maps unambiguously back to a source row.
 */
@Component
public class ObservationFhirResourceProvider implements IResourceProvider {

    private static final int MAX_VITALS_PER_PATIENT = 250;
    private static final int MAX_LAB_RESULTS_PER_PATIENT = 250;
    private static final String VITAL_PREFIX = "vital-";
    private static final int UUID_LENGTH = 36;

    private final PatientVitalSignRepository vitalsRepository;
    private final LabResultRepository labResultRepository;
    private final ObservationFhirMapper mapper;
    private final ObservationFhirWriteService writeService;

    public ObservationFhirResourceProvider(
        PatientVitalSignRepository vitalsRepository,
        LabResultRepository labResultRepository,
        ObservationFhirMapper mapper,
        ObservationFhirWriteService writeService
    ) {
        this.vitalsRepository = vitalsRepository;
        this.labResultRepository = labResultRepository;
        this.mapper = mapper;
        this.writeService = writeService;
    }

    @Override
    public Class<Observation> getResourceType() {
        return Observation.class;
    }

    @Read
    public Observation read(@IdParam IdType id) {
        if (id == null || id.getIdPart() == null) {
            throw new ResourceNotFoundException(id);
        }
        // Same tenant contract as the write path and $everything: an active
        // hospital scope is required, and a row belonging to another
        // hospital collapses to not-found — cross-tenant rejection stays
        // invisible. The previous bare findById here was a cross-tenant
        // PHI read for anyone holding a row UUID.
        UUID hospitalId = requireHospitalScope();
        String idPart = id.getIdPart();
        if (idPart.startsWith("labresult-")) {
            UUID uuid = FhirIds.tryParse(idPart.substring("labresult-".length()));
            if (uuid == null) throw new ResourceNotFoundException(id);
            return labResultRepository.findById(uuid)
                .filter(r -> r.getLabOrder() != null && r.getLabOrder().getHospital() != null
                    && hospitalId.equals(r.getLabOrder().getHospital().getId()))
                .map(mapper::toFhir)
                .orElseThrow(() -> new ResourceNotFoundException(id));
        }
        if (idPart.startsWith(VITAL_PREFIX)) {
            // Format: vital-{uuid}-{component}. Components emitted by the mapper
            // include dashes themselves (heart-rate, resp-rate), so we cannot
            // split on the last dash — UUID is fixed at 36 characters.
            int uuidStart = VITAL_PREFIX.length();
            int uuidEnd = uuidStart + UUID_LENGTH;
            if (idPart.length() <= uuidEnd + 1 || idPart.charAt(uuidEnd) != '-') {
                throw new ResourceNotFoundException(id);
            }
            UUID uuid = FhirIds.tryParse(idPart.substring(uuidStart, uuidEnd));
            if (uuid == null) throw new ResourceNotFoundException(id);
            String component = idPart.substring(uuidEnd + 1);
            return vitalsRepository.findById(uuid)
                .filter(v -> v.getHospital() != null
                    && hospitalId.equals(v.getHospital().getId()))
                .map(mapper::toFhir)
                .flatMap(list -> list.stream()
                    .filter(o -> o.getId() != null && o.getId().endsWith("-" + component))
                    .findFirst())
                .orElseThrow(() -> new ResourceNotFoundException(id));
        }
        throw new ResourceNotFoundException(id);
    }

    @Search
    public List<Observation> search(
        @OptionalParam(name = "patient") ReferenceParam patient,
        @OptionalParam(name = "subject") ReferenceParam subject,
        @OptionalParam(name = "category") TokenOrListParam category
    ) {
        UUID patientId = FhirIds.fromReference(patient != null ? patient : subject);
        if (patientId == null) return List.of();

        // Hospital-scoped queries (the $everything contract): the previous
        // patient-only queries let any authenticated caller pull ANY
        // patient's vitals + labs by guessing the patient UUID.
        UUID hospitalId = requireHospitalScope();
        boolean wantsVitals = wantsCategory(category, "vital-signs", true);
        boolean wantsLabs = wantsCategory(category, "laboratory", true);

        List<Observation> out = new ArrayList<>();
        if (wantsVitals) {
            vitalsRepository.findPageByPatient_IdAndHospital_IdOrderByRecordedAtDesc(
                    patientId, hospitalId, PageRequest.of(0, MAX_VITALS_PER_PATIENT))
                .forEach(v -> out.addAll(mapper.toFhir(v)));
        }
        if (wantsLabs) {
            labResultRepository.findPageByLabOrder_Patient_IdAndLabOrder_Hospital_Id(
                    patientId, hospitalId, PageRequest.of(0, MAX_LAB_RESULTS_PER_PATIENT))
                .forEach(r -> {
                    Observation mapped = mapper.toFhir(r);
                    if (mapped != null) out.add(mapped);
                });
        }
        return out;
    }

    /**
     * The read-side tenant anchor, mirroring ObservationFhirWriteService
     * and Patient/{id}/$everything: no active hospital scope means no
     * answer — a super-admin must pin X-Hospital-Id, exactly as on the
     * write path.
     */
    private static UUID requireHospitalScope() {
        UUID hospitalId = HospitalContextHolder.getContextOrEmpty().getActiveHospitalId();
        if (hospitalId == null) {
            OperationOutcome outcome = new OperationOutcome();
            outcome.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setCode(OperationOutcome.IssueType.FORBIDDEN)
                .setDiagnostics("FHIR Observation reads require an active hospital scope; "
                    + "supply X-Hospital-Id or authenticate as a hospital-scoped user.");
            throw new ForbiddenOperationException("An active hospital scope is required.", outcome);
        }
        return hospitalId;
    }

    private static boolean wantsCategory(TokenOrListParam category, String code, boolean defaultIfMissing) {
        if (category == null || category.getValuesAsQueryTokens().isEmpty()) return defaultIfMissing;
        return category.getValuesAsQueryTokens().stream()
            .map(TokenParam::getValue)
            .anyMatch(v -> v != null && v.equalsIgnoreCase(code));
    }

    /**
     * PUT /Observation/{id}. Only the {@code labresult-{uuid}} namespace
     * is writable — {@code vital-*} ids are rejected with 422
     * BUSINESSRULE (1:N read-side expansion has no single-row write
     * target). Honored fields are restricted to {@code note[0].text}.
     *
     * <p>Feature-flagged: when {@code app.fhir.write.enabled=false}
     * (default) the handler returns 405 <strong>before</strong> any
     * id-shape or body validation.
     */
    @Update
    public MethodOutcome update(
        @IdParam IdType id,
        @ResourceParam Observation resource
    ) {
        if (!writeService.isEnabled()) {
            throw new MethodNotAllowedException(
                "FHIR write API is disabled — set app.fhir.write.enabled=true to opt in."
            );
        }
        if (resource == null) {
            throw unprocessable(
                "PUT /Observation/{id} requires an Observation resource body.",
                OperationOutcome.IssueType.STRUCTURE
            );
        }
        if (id == null || id.getIdPart() == null || id.getIdPart().isBlank()) {
            throw new ResourceNotFoundException(id);
        }
        String idPart = id.getIdPart();
        if (resource.getIdElement() != null && resource.getIdElement().getIdPart() != null
            && !resource.getIdElement().getIdPart().isBlank()
            && !resource.getIdElement().getIdPart().equals(idPart)) {
            throw unprocessable(
                "Resource.id does not match the URL id; refusing to honor PUT against a mismatched id.",
                OperationOutcome.IssueType.BUSINESSRULE
            );
        }
        LabResult saved = writeService.updateLabResult(idPart, resource);
        Observation rendered = mapper.toFhir(saved);
        return new MethodOutcome()
            .setId(new IdType("Observation", rendered != null && rendered.getId() != null
                ? rendered.getId() : idPart))
            .setResource(rendered);
    }

    private static UnprocessableEntityException unprocessable(String message, OperationOutcome.IssueType type) {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(type)
            .setDiagnostics(message);
        return new UnprocessableEntityException(message, outcome);
    }
}
