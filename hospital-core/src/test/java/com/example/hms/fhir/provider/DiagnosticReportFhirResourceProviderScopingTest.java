package com.example.hms.fhir.provider;

import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.fhir.mapper.DiagnosticReportFhirMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.MicroCultureResult;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.MicroCultureResultRepository;
import com.example.hms.repository.MicroIsolateRepository;
import com.example.hms.repository.MicroSusceptibilityRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.hl7.fhir.r4.model.IdType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
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
 * and another hospital's row collapses to not-found — pinned for EVERY
 * namespace, because a guard tested only on one branch can be deleted from
 * the others unnoticed. Every foreign-row test stubs the mapper non-null:
 * an unstubbed mock returns null and the read collapses to not-found even
 * with the guard deleted, which the first mutation run caught as a false
 * guarantee.
 */
@ExtendWith(MockitoExtension.class)
class DiagnosticReportFhirResourceProviderScopingTest {

    @Mock private LabOrderRepository labOrderRepository;
    @Mock private LabResultRepository labResultRepository;
    @Mock private MicroCultureResultRepository microCultureRepository;
    @Mock private MicroIsolateRepository microIsolateRepository;
    @Mock private MicroSusceptibilityRepository microSusceptibilityRepository;
    @Mock private ImagingReportRepository imagingReportRepository;
    @Mock private DiagnosticReportFhirMapper mapper;

    private DiagnosticReportFhirResourceProvider provider;
    private UUID activeHospitalId;

    @BeforeEach
    void setUp() {
        provider = new DiagnosticReportFhirResourceProvider(labOrderRepository,
            labResultRepository, microCultureRepository, microIsolateRepository,
            microSusceptibilityRepository, imagingReportRepository, mapper);
        activeHospitalId = UUID.randomUUID();
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(activeHospitalId).build());
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
    }

    private static Hospital hospitalWithId(UUID id) {
        Hospital hospital = new Hospital();
        hospital.setId(id);
        return hospital;
    }

    @Test
    @DisplayName("no hospital scope is a hard 403 before any repository is touched")
    void readWithoutScopeIsForbidden() {
        HospitalContextHolder.clear();
        IdType id = new IdType("laborder-" + UUID.randomUUID());

        assertThrows(ForbiddenOperationException.class, () -> provider.read(id));
        verifyNoInteractions(labOrderRepository, labResultRepository,
            microCultureRepository, imagingReportRepository);
    }

    @Test
    @DisplayName("another hospital's lab order collapses to not-found")
    void foreignLabOrderIsNotFound() {
        UUID orderId = UUID.randomUUID();
        LabOrder foreign = new LabOrder();
        foreign.setId(orderId);
        foreign.setHospital(hospitalWithId(UUID.randomUUID()));
        when(labOrderRepository.findById(orderId)).thenReturn(Optional.of(foreign));
        Mockito.lenient().when(mapper.toFhir(eq(foreign), any()))
            .thenReturn(new org.hl7.fhir.r4.model.DiagnosticReport());
        IdType id = new IdType("laborder-" + orderId);

        assertThrows(ResourceNotFoundException.class, () -> provider.read(id));
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("another hospital's culture collapses to not-found")
    void foreignCultureIsNotFound() {
        UUID cultureId = UUID.randomUUID();
        MicroCultureResult foreign = new MicroCultureResult();
        foreign.setId(cultureId);
        foreign.setHospital(hospitalWithId(UUID.randomUUID()));
        when(microCultureRepository.findById(cultureId)).thenReturn(Optional.of(foreign));
        Mockito.lenient().when(mapper.toFhir(eq(foreign), any(), any()))
            .thenReturn(new org.hl7.fhir.r4.model.DiagnosticReport());
        IdType id = new IdType("micro-" + cultureId);

        assertThrows(ResourceNotFoundException.class, () -> provider.read(id));
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("another hospital's imaging report collapses to not-found")
    void foreignImagingReportIsNotFound() {
        UUID reportId = UUID.randomUUID();
        ImagingReport foreign = new ImagingReport();
        foreign.setId(reportId);
        foreign.setHospital(hospitalWithId(UUID.randomUUID()));
        when(imagingReportRepository.findById(reportId)).thenReturn(Optional.of(foreign));
        Mockito.lenient().when(mapper.toFhir(foreign))
            .thenReturn(new org.hl7.fhir.r4.model.DiagnosticReport());
        IdType id = new IdType("imgreport-" + reportId);

        assertThrows(ResourceNotFoundException.class, () -> provider.read(id));
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("an unknown namespace is not-found, not a stack trace")
    void unknownNamespaceIsNotFound() {
        IdType id = new IdType("bogus-" + UUID.randomUUID());

        assertThrows(ResourceNotFoundException.class, () -> provider.read(id));
    }

    @Test
    @DisplayName("search emits reports only for orders that have at least one result")
    void searchSkipsResultlessOrders() {
        UUID patientId = UUID.randomUUID();
        LabOrder withResults = orderAt(activeHospitalId);
        LabOrder withoutResults = orderAt(activeHospitalId);
        when(labOrderRepository.findByPatient_IdAndHospital_IdOrderByOrderDatetimeDesc(
            eq(patientId), eq(activeHospitalId), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(withResults, withoutResults)));
        LabResult result = new LabResult();
        result.setLabOrder(withResults);
        // Batch-loaded in one query for the page, then grouped — the
        // per-order findByLabOrder_Id round trips are gone.
        when(labResultRepository.findByLabOrder_IdIn(
            List.of(withResults.getId(), withoutResults.getId())))
            .thenReturn(List.of(result));
        when(mapper.toFhir(eq(withResults), any()))
            .thenReturn(new org.hl7.fhir.r4.model.DiagnosticReport());
        when(microCultureRepository.findByPatient_IdAndHospital_IdOrderByCreatedAtDesc(
            eq(patientId), eq(activeHospitalId), any(Pageable.class)))
            .thenReturn(Page.empty());
        when(imagingReportRepository
            .findByImagingOrder_Patient_IdAndHospital_IdAndLatestVersionIsTrueOrderByPerformedAtDesc(
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
        order.setHospital(hospitalWithId(hospitalId));
        return order;
    }
}
