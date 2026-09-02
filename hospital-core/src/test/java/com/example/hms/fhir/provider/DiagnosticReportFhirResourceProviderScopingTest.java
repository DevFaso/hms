package com.example.hms.fhir.provider;

import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.fhir.mapper.DiagnosticReportFhirMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.MicroCultureResultRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.hl7.fhir.r4.model.IdType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
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
 * The DiagnosticReport provider inherits the Observation provider's tenant
 * contract wholesale: no scope is a hard 403 before any repository call,
 * and another hospital's row collapses to not-found. These pin it, plus the
 * one search rule of its own — an order with no results is not a report.
 */
@ExtendWith(MockitoExtension.class)
class DiagnosticReportFhirResourceProviderScopingTest {

    @Mock private LabOrderRepository labOrderRepository;
    @Mock private LabResultRepository labResultRepository;
    @Mock private MicroCultureResultRepository microCultureRepository;
    @Mock private ImagingOrderRepository imagingOrderRepository;
    @Mock private ImagingReportRepository imagingReportRepository;
    @Mock private DiagnosticReportFhirMapper mapper;

    private DiagnosticReportFhirResourceProvider provider;
    private UUID activeHospitalId;

    @BeforeEach
    void setUp() {
        provider = new DiagnosticReportFhirResourceProvider(labOrderRepository,
            labResultRepository, microCultureRepository, imagingOrderRepository,
            imagingReportRepository, mapper);
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
            () -> provider.read(new IdType("laborder-" + UUID.randomUUID())));
        verifyNoInteractions(labOrderRepository, labResultRepository,
            microCultureRepository, imagingReportRepository);
    }

    @Test
    @DisplayName("another hospital's lab order collapses to not-found")
    void foreignLabOrderIsNotFound() {
        UUID orderId = UUID.randomUUID();
        LabOrder foreign = new LabOrder();
        foreign.setId(orderId);
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        foreign.setHospital(other);
        when(labOrderRepository.findById(orderId)).thenReturn(Optional.of(foreign));
        // Lenient non-null stub: with the tenant filter in place the mapper
        // is never reached. Without it, an UNSTUBBED mock would return null
        // and the read would still collapse to not-found - the test would
        // pass with the guard deleted, which is exactly the false guarantee
        // a mutation run caught here.
        org.mockito.Mockito.lenient().when(mapper.toFhir(eq(foreign), any()))
            .thenReturn(new org.hl7.fhir.r4.model.DiagnosticReport());

        assertThrows(ResourceNotFoundException.class,
            () -> provider.read(new IdType("laborder-" + orderId)));
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("an unknown namespace is not-found, not a stack trace")
    void unknownNamespaceIsNotFound() {
        assertThrows(ResourceNotFoundException.class,
            () -> provider.read(new IdType("bogus-" + UUID.randomUUID())));
    }

    @Test
    @DisplayName("search emits reports only for orders that have at least one result")
    void searchSkipsResultlessOrders() {
        UUID patientId = UUID.randomUUID();
        LabOrder withResults = orderAt(activeHospitalId);
        LabOrder withoutResults = orderAt(activeHospitalId);
        Page<LabOrder> page = new PageImpl<>(List.of(withResults, withoutResults));
        when(labOrderRepository.findByPatient_IdAndHospital_IdOrderByOrderDatetimeDesc(
            eq(patientId), eq(activeHospitalId), any(Pageable.class))).thenReturn(page);
        when(labResultRepository.findByLabOrder_Id(withResults.getId()))
            .thenReturn(List.of(new LabResult()));
        when(labResultRepository.findByLabOrder_Id(withoutResults.getId()))
            .thenReturn(List.of());
        when(mapper.toFhir(eq(withResults), any()))
            .thenReturn(new org.hl7.fhir.r4.model.DiagnosticReport());
        when(microCultureRepository.findByPatient_IdAndHospital_IdOrderByCreatedAtDesc(
            eq(patientId), eq(activeHospitalId), any(Pageable.class)))
            .thenReturn(Page.empty());
        when(imagingOrderRepository.findByPatient_IdAndHospital_IdOrderByOrderedAtDesc(
            eq(patientId), eq(activeHospitalId), any(Pageable.class)))
            .thenReturn(Page.empty());

        var out = provider.search(new ReferenceParam(patientId.toString()), null);

        assertThat(out).hasSize(1);
    }

    @Test
    @DisplayName("search without a patient returns empty rather than the whole hospital")
    void searchWithoutPatientIsEmpty() {
        assertThat(provider.search(null, null)).isEmpty();
        verifyNoInteractions(labOrderRepository);
    }

    private static LabOrder orderAt(UUID hospitalId) {
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        order.setHospital(hospital);
        return order;
    }
}
