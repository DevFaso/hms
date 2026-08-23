package com.example.hms.fhir.provider;

import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.fhir.mapper.ObservationFhirMapper;
import com.example.hms.fhir.write.ObservationFhirWriteService;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Observation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tenant-scope contract of the Observation read/search paths: no active
 * hospital scope is a hard 403 (the write-side / $everything contract),
 * and a row belonging to another hospital collapses to not-found. Before
 * this guard, a bare findById here let any authenticated caller read any
 * hospital's lab result or vital sign by row UUID.
 */
@ExtendWith(MockitoExtension.class)
class ObservationFhirResourceProviderScopingTest {

    @Mock
    private PatientVitalSignRepository vitalsRepository;
    @Mock
    private LabResultRepository labResultRepository;
    @Mock
    private ObservationFhirMapper mapper;
    @Mock
    private ObservationFhirWriteService writeService;

    private ObservationFhirResourceProvider provider;

    private UUID activeHospitalId;

    @BeforeEach
    void setUp() {
        provider = new ObservationFhirResourceProvider(
            vitalsRepository, labResultRepository, mapper, writeService);
        activeHospitalId = UUID.randomUUID();
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(activeHospitalId).build());
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
    }

    @Test
    void readWithoutHospitalScopeIsForbidden() {
        HospitalContextHolder.clear();

        assertThrows(ForbiddenOperationException.class,
            () -> provider.read(new IdType("Observation", "labresult-" + UUID.randomUUID())));
        verifyNoInteractions(labResultRepository, vitalsRepository, mapper);
    }

    @Test
    void readLabResultFromAnotherHospitalReadsAsNotFound() {
        UUID rowId = UUID.randomUUID();
        LabResult foreign = labResultInHospital(rowId, UUID.randomUUID());
        when(labResultRepository.findById(rowId)).thenReturn(Optional.of(foreign));

        assertThrows(ResourceNotFoundException.class,
            () -> provider.read(new IdType("Observation", "labresult-" + rowId)));
        verifyNoInteractions(mapper);
    }

    @Test
    void readLabResultInActiveHospitalMaps() {
        UUID rowId = UUID.randomUUID();
        LabResult own = labResultInHospital(rowId, activeHospitalId);
        Observation mapped = new Observation();
        mapped.setId("labresult-" + rowId);
        when(labResultRepository.findById(rowId)).thenReturn(Optional.of(own));
        when(mapper.toFhir(own)).thenReturn(mapped);

        Observation out = provider.read(new IdType("Observation", "labresult-" + rowId));

        assertThat(out).isSameAs(mapped);
    }

    @Test
    void readVitalFromAnotherHospitalReadsAsNotFound() {
        UUID rowId = UUID.randomUUID();
        PatientVitalSign vital = new PatientVitalSign();
        vital.setId(rowId);
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        vital.setHospital(other);
        when(vitalsRepository.findById(rowId)).thenReturn(Optional.of(vital));

        assertThrows(ResourceNotFoundException.class,
            () -> provider.read(new IdType("Observation", "vital-" + rowId + "-heart-rate")));
        verifyNoInteractions(mapper);
    }

    @Test
    void searchWithoutHospitalScopeIsForbidden() {
        HospitalContextHolder.clear();
        ReferenceParam patient = new ReferenceParam(UUID.randomUUID().toString());

        assertThrows(ForbiddenOperationException.class,
            () -> provider.search(patient, null, null));
        verifyNoInteractions(labResultRepository, vitalsRepository);
    }

    @Test
    void searchQueriesAreHospitalScoped() {
        UUID patientId = UUID.randomUUID();
        when(vitalsRepository.findPageByPatient_IdAndHospital_IdOrderByRecordedAtDesc(
            eq(patientId), eq(activeHospitalId), any(Pageable.class)))
            .thenReturn(Page.empty());
        when(labResultRepository.findPageByLabOrder_Patient_IdAndLabOrder_Hospital_Id(
            eq(patientId), eq(activeHospitalId), any(Pageable.class)))
            .thenReturn(Page.empty());

        List<Observation> out = provider.search(new ReferenceParam(patientId.toString()), null, null);

        assertThat(out).isEmpty();
        // The point: both queries carry the active hospital id — the old
        // patient-only queries answered for every hospital at once.
        verify(vitalsRepository).findPageByPatient_IdAndHospital_IdOrderByRecordedAtDesc(
            eq(patientId), eq(activeHospitalId), any(Pageable.class));
        verify(labResultRepository).findPageByLabOrder_Patient_IdAndLabOrder_Hospital_Id(
            eq(patientId), eq(activeHospitalId), any(Pageable.class));
    }

    @Test
    void searchWithoutPatientReturnsEmptyWithoutTouchingRepositories() {
        List<Observation> out = provider.search(null, null, null);

        assertThat(out).isEmpty();
        verifyNoInteractions(labResultRepository, vitalsRepository);
        verify(mapper, never()).toFhir(any(LabResult.class));
    }

    private static LabResult labResultInHospital(UUID rowId, UUID hospitalId) {
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        LabOrder order = new LabOrder();
        order.setHospital(hospital);
        LabResult result = new LabResult();
        result.setId(rowId);
        result.setLabOrder(order);
        return result;
    }
}
