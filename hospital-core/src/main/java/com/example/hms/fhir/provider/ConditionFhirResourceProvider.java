package com.example.hms.fhir.provider;

import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.fhir.mapper.ConditionFhirMapper;
import com.example.hms.repository.PatientProblemRepository;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.IdType;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
public class ConditionFhirResourceProvider implements IResourceProvider {

    private final PatientProblemRepository repository;
    private final ConditionFhirMapper mapper;

    public ConditionFhirResourceProvider(PatientProblemRepository repository, ConditionFhirMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Class<Condition> getResourceType() {
        return Condition.class;
    }

    @Read
    public Condition read(@IdParam IdType id) {
        UUID uuid = FhirIds.parseOrThrow(id);
        return repository.findById(uuid)
            .map(mapper::toFhir)
            .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    @Search
    public List<Condition> search(
        @OptionalParam(name = "patient") ReferenceParam patient,
        @OptionalParam(name = "subject") ReferenceParam subject
    ) {
        UUID patientId = FhirIds.fromReference(patient != null ? patient : subject);
        if (patientId == null) return Collections.emptyList();
        return repository.findByPatient_Id(patientId).stream()
            .map(mapper::toFhir)
            .toList();
    }
}
