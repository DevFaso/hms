package com.example.hms.fhir.provider;

import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.fhir.mapper.EncounterFhirMapper;
import com.example.hms.repository.EncounterRepository;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.IdType;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
public class EncounterFhirResourceProvider implements IResourceProvider {

    private final EncounterRepository encounterRepository;
    private final EncounterFhirMapper mapper;

    public EncounterFhirResourceProvider(EncounterRepository encounterRepository, EncounterFhirMapper mapper) {
        this.encounterRepository = encounterRepository;
        this.mapper = mapper;
    }

    @Override
    public Class<Encounter> getResourceType() {
        return Encounter.class;
    }

    @Read
    public Encounter read(@IdParam IdType id) {
        UUID uuid = FhirIds.parseOrThrow(id);
        return encounterRepository.findById(uuid)
            .map(mapper::toFhir)
            .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    @Search
    public List<Encounter> search(
        @OptionalParam(name = "patient") ReferenceParam patient,
        @OptionalParam(name = "subject") ReferenceParam subject,
        @OptionalParam(name = "_id") TokenParam idParam
    ) {
        if (idParam != null && idParam.getValue() != null) {
            UUID uuid = FhirIds.tryParse(idParam.getValue());
            if (uuid == null) return Collections.emptyList();
            return encounterRepository.findById(uuid).map(mapper::toFhir).map(List::of).orElseGet(Collections::emptyList);
        }
        UUID patientId = FhirIds.fromReference(patient != null ? patient : subject);
        if (patientId == null) return Collections.emptyList();
        return encounterRepository.findByPatient_Id(patientId).stream()
            .map(mapper::toFhir)
            .toList();
    }
}
