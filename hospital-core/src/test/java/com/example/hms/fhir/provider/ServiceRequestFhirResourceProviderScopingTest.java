package com.example.hms.fhir.provider;

import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.fhir.mapper.ServiceRequestFhirMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.LabOrder;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ServiceRequest provider tenant contract (Tier 2 item 42): scope required
 * before any repository call, foreign rows collapse to not-found, and the
 * search merges both order domains under one patient.
 */
@ExtendWith(MockitoExtension.class)
class ServiceRequestFhirResourceProviderScopingTest {

    @Mock private LabOrderRepository labOrderRepository;
    @Mock private ImagingOrderRepository imagingOrderRepository;
    @Mock private ServiceRequestFhirMapper mapper;

    private ServiceRequestFhirResourceProvider provider;
    private UUID activeHospitalId;

    @BeforeEach
    void setUp() {
        provider = new ServiceRequestFhirResourceProvider(
            labOrderRepository, imagingOrderRepository, mapper);
        activeHospitalId = UUID.randomUUID();
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(activeHospitalId).build());
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("no hospital scope is a hard 403 before any repository is touched")
    void readWithoutScopeIsForbidden() {
        HospitalContextHolder.clear();
        assertThrows(ForbiddenOperationException.class,
            () -> provider.read(new IdType("imgorder-" + UUID.randomUUID())));
        verifyNoInteractions(labOrderRepository, imagingOrderRepository);
    }

    @Test
    @DisplayName("another hospital's imaging order collapses to not-found")
    void foreignImagingOrderIsNotFound() {
        UUID orderId = UUID.randomUUID();
        ImagingOrder foreign = new ImagingOrder();
        foreign.setId(orderId);
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        foreign.setHospital(other);
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(foreign));
        // Lenient non-null stub - see the DiagnosticReport twin: an
        // unstubbed mapper returns null and hides a deleted tenant filter.
        org.mockito.Mockito.lenient().when(mapper.toFhir(foreign))
            .thenReturn(new ServiceRequest());

        assertThrows(ResourceNotFoundException.class,
            () -> provider.read(new IdType("imgorder-" + orderId)));
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("a same-hospital lab order resolves through the laborder namespace")
    void ownLabOrderResolves() {
        UUID orderId = UUID.randomUUID();
        LabOrder own = new LabOrder();
        own.setId(orderId);
        Hospital hospital = new Hospital();
        hospital.setId(activeHospitalId);
        own.setHospital(hospital);
        when(labOrderRepository.findById(orderId)).thenReturn(Optional.of(own));
        when(mapper.toFhir(own)).thenReturn(new ServiceRequest());

        assertThat(provider.read(new IdType("laborder-" + orderId))).isNotNull();
    }

    @Test
    @DisplayName("search merges lab and imaging orders for one patient")
    void searchMergesBothDomains() {
        UUID patientId = UUID.randomUUID();
        LabOrder lab = new LabOrder();
        lab.setId(UUID.randomUUID());
        ImagingOrder imaging = new ImagingOrder();
        imaging.setId(UUID.randomUUID());
        when(labOrderRepository.findByPatient_IdAndHospital_IdOrderByOrderDatetimeDesc(
            eq(patientId), eq(activeHospitalId), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(lab)));
        when(imagingOrderRepository.findByPatient_IdAndHospital_IdOrderByOrderedAtDesc(
            eq(patientId), eq(activeHospitalId), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(imaging)));
        when(mapper.toFhir(lab)).thenReturn(new ServiceRequest());
        when(mapper.toFhir(imaging)).thenReturn(new ServiceRequest());

        assertThat(provider.search(new ReferenceParam(patientId.toString()), null)).hasSize(2);
    }

    @Test
    @DisplayName("search without a patient returns empty rather than the whole hospital")
    void searchWithoutPatientIsEmpty() {
        assertThat(provider.search(null, null)).isEmpty();
        verifyNoInteractions(labOrderRepository, imagingOrderRepository);
    }
}
