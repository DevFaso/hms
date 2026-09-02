package com.example.hms.fhir.provider;

import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.fhir.mapper.ServiceRequestFhirMapper;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.LabOrderRepository;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * FHIR R4 {@code ServiceRequest} provider (Tier 2 item 42), sourced from the
 * two order domains: lab orders ({@code laborder-{uuid}}) and imaging orders
 * ({@code imgorder-{uuid}}).
 *
 * <p>Read-only on purpose — orders carry ceremonies (signature, contrast
 * safety, standing-order review) that live in their own surfaces, and a FHIR
 * write path would be a way around all of them.
 *
 * <p>Tenant contract identical to the Observation provider: an active
 * hospital scope is required, and another hospital's row collapses to
 * not-found.
 */
@Component
public class ServiceRequestFhirResourceProvider implements IResourceProvider {

    private static final int MAX_ORDERS_PER_PATIENT = 200;
    private static final String LAB_PREFIX = "laborder-";
    private static final String IMAGING_PREFIX = "imgorder-";

    private final LabOrderRepository labOrderRepository;
    private final ImagingOrderRepository imagingOrderRepository;
    private final ServiceRequestFhirMapper mapper;

    public ServiceRequestFhirResourceProvider(
        LabOrderRepository labOrderRepository,
        ImagingOrderRepository imagingOrderRepository,
        ServiceRequestFhirMapper mapper
    ) {
        this.labOrderRepository = labOrderRepository;
        this.imagingOrderRepository = imagingOrderRepository;
        this.mapper = mapper;
    }

    @Override
    public Class<ServiceRequest> getResourceType() {
        return ServiceRequest.class;
    }

    @Read
    public ServiceRequest read(@IdParam IdType id) {
        if (id == null || id.getIdPart() == null) {
            throw new ResourceNotFoundException(id);
        }
        UUID hospitalId = requireHospitalScope();
        String idPart = id.getIdPart();
        if (idPart.startsWith(LAB_PREFIX)) {
            UUID uuid = FhirIds.tryParse(idPart.substring(LAB_PREFIX.length()));
            if (uuid == null) throw new ResourceNotFoundException(id);
            return labOrderRepository.findById(uuid)
                .filter(o -> o.getHospital() != null && hospitalId.equals(o.getHospital().getId()))
                .map(mapper::toFhir)
                .orElseThrow(() -> new ResourceNotFoundException(id));
        }
        if (idPart.startsWith(IMAGING_PREFIX)) {
            UUID uuid = FhirIds.tryParse(idPart.substring(IMAGING_PREFIX.length()));
            if (uuid == null) throw new ResourceNotFoundException(id);
            return imagingOrderRepository.findById(uuid)
                .filter(o -> o.getHospital() != null && hospitalId.equals(o.getHospital().getId()))
                .map(mapper::toFhir)
                .orElseThrow(() -> new ResourceNotFoundException(id));
        }
        throw new ResourceNotFoundException(id);
    }

    @Search
    public List<ServiceRequest> search(
        @OptionalParam(name = "patient") ReferenceParam patient,
        @OptionalParam(name = "subject") ReferenceParam subject
    ) {
        UUID patientId = FhirIds.fromReference(patient != null ? patient : subject);
        if (patientId == null) return List.of();
        UUID hospitalId = requireHospitalScope();

        List<ServiceRequest> out = new ArrayList<>();
        labOrderRepository.findByPatient_IdAndHospital_IdOrderByOrderDatetimeDesc(
                patientId, hospitalId, PageRequest.of(0, MAX_ORDERS_PER_PATIENT))
            .forEach(o -> {
                ServiceRequest mapped = mapper.toFhir(o);
                if (mapped != null) out.add(mapped);
            });
        imagingOrderRepository.findByPatient_IdAndHospital_IdOrderByOrderedAtDesc(
                patientId, hospitalId, PageRequest.of(0, MAX_ORDERS_PER_PATIENT))
            .forEach(o -> {
                ServiceRequest mapped = mapper.toFhir(o);
                if (mapped != null) out.add(mapped);
            });
        return out;
    }

    private static UUID requireHospitalScope() {
        return FhirTenancy.requireHospitalScope("ServiceRequest");
    }
}
