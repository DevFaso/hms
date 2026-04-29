package com.example.hms.fhir.provider;

import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenOrListParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.fhir.mapper.ObservationFhirMapper;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Observation;
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

    public ObservationFhirResourceProvider(
        PatientVitalSignRepository vitalsRepository,
        LabResultRepository labResultRepository,
        ObservationFhirMapper mapper
    ) {
        this.vitalsRepository = vitalsRepository;
        this.labResultRepository = labResultRepository;
        this.mapper = mapper;
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
        String idPart = id.getIdPart();
        if (idPart.startsWith("labresult-")) {
            UUID uuid = FhirIds.tryParse(idPart.substring("labresult-".length()));
            if (uuid == null) throw new ResourceNotFoundException(id);
            return labResultRepository.findById(uuid)
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

        boolean wantsVitals = wantsCategory(category, "vital-signs", true);
        boolean wantsLabs = wantsCategory(category, "laboratory", true);

        List<Observation> out = new ArrayList<>();
        if (wantsVitals) {
            vitalsRepository.findByPatient_IdOrderByRecordedAtDesc(patientId, PageRequest.of(0, MAX_VITALS_PER_PATIENT))
                .forEach(v -> out.addAll(mapper.toFhir(v)));
        }
        if (wantsLabs) {
            labResultRepository.findByLabOrder_Patient_Id(patientId).stream()
                .limit(MAX_LAB_RESULTS_PER_PATIENT)
                .forEach(r -> {
                    Observation mapped = mapper.toFhir(r);
                    if (mapped != null) out.add(mapped);
                });
        }
        return out;
    }

    private static boolean wantsCategory(TokenOrListParam category, String code, boolean defaultIfMissing) {
        if (category == null || category.getValuesAsQueryTokens().isEmpty()) return defaultIfMissing;
        return category.getValuesAsQueryTokens().stream()
            .map(TokenParam::getValue)
            .anyMatch(v -> v != null && v.equalsIgnoreCase(code));
    }
}
