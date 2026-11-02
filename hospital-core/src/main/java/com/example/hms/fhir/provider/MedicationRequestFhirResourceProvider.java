package com.example.hms.fhir.provider;

import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.fhir.mapper.MedicationRequestFhirMapper;
import com.example.hms.repository.PrescriptionRepository;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
public class MedicationRequestFhirResourceProvider implements IResourceProvider {

    private static final int MAX_RESULTS = 200;
    /** QA D1295 class: the capped page keeps the newest orders, not whatever the database returns first. */
    public static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "id");

    private final PrescriptionRepository repository;
    private final MedicationRequestFhirMapper mapper;

    public MedicationRequestFhirResourceProvider(PrescriptionRepository repository,
                                                 MedicationRequestFhirMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Class<MedicationRequest> getResourceType() {
        return MedicationRequest.class;
    }

    @Read
    public MedicationRequest read(@IdParam IdType id) {
        UUID uuid = FhirIds.parseOrThrow(id);
        return repository.findById(uuid)
            .map(mapper::toFhir)
            .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    @Search
    public List<MedicationRequest> search(
        @OptionalParam(name = "patient") ReferenceParam patient,
        @OptionalParam(name = "subject") ReferenceParam subject
    ) {
        UUID patientId = FhirIds.fromReference(patient != null ? patient : subject);
        if (patientId == null) return Collections.emptyList();
        return repository.findByPatient_Id(patientId, PageRequest.of(0, MAX_RESULTS, NEWEST_FIRST))
            .stream()
            .map(mapper::toFhir)
            .toList();
    }
}
