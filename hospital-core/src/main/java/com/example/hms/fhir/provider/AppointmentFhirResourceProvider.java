package com.example.hms.fhir.provider;

import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.fhir.mapper.AppointmentFhirMapper;
import com.example.hms.repository.AppointmentRepository;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.IdType;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * FHIR R4 {@code Appointment} provider (Tier 2 item 43). Read-only —
 * booking goes through the slot writer with its hold/version ceremony.
 *
 * <p>Tenant contract identical to the other providers: an active hospital
 * scope is required, and another hospital's row collapses to not-found.
 */
@Component
// Read-only TX: open-in-view=false, so without it the mapper's walk over
// lazily loaded associations happens after the repository transaction has
// closed and throws LazyInitializationException on perfectly valid reads.
// readOnly also keeps the query eligible for read-replica routing.
@org.springframework.transaction.annotation.Transactional(readOnly = true)
public class AppointmentFhirResourceProvider implements IResourceProvider {

    private static final int MAX_PER_PATIENT = 200;

    private final AppointmentRepository appointmentRepository;
    private final AppointmentFhirMapper mapper;

    public AppointmentFhirResourceProvider(AppointmentRepository appointmentRepository,
                                           AppointmentFhirMapper mapper) {
        this.appointmentRepository = appointmentRepository;
        this.mapper = mapper;
    }

    @Override
    public Class<Appointment> getResourceType() {
        return Appointment.class;
    }

    @Read
    public Appointment read(@IdParam IdType id) {
        UUID hospitalId = FhirTenancy.requireHospitalScope("Appointment");
        UUID uuid = FhirIds.parseOrThrow(id);
        return appointmentRepository.findById(uuid)
            .filter(a -> a.getHospital() != null && hospitalId.equals(a.getHospital().getId()))
            .map(mapper::toFhir)
            .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    /**
     * Patient search only, on purpose. FHIR's {@code actor} names ANY
     * participant — treating an actor UUID as a patient id would silently
     * miss a practitioner's appointments or match an unrelated patient, so
     * the parameter is not offered rather than half-honoured.
     */
    @Search
    public List<Appointment> search(
        @OptionalParam(name = "patient") ReferenceParam patient,
        @OptionalParam(name = "subject") ReferenceParam subject
    ) {
        UUID patientId = FhirIds.fromReference(patient != null ? patient : subject);
        if (patientId == null) return List.of();
        UUID hospitalId = FhirTenancy.requireHospitalScope("Appointment");
        return appointmentRepository
            .findByHospital_IdAndPatient_IdOrderByAppointmentDateDescStartTimeDesc(
                hospitalId, patientId, PageRequest.of(0, MAX_PER_PATIENT))
            .map(mapper::toFhir)
            .filter(java.util.Objects::nonNull)
            .toList();
    }
}
