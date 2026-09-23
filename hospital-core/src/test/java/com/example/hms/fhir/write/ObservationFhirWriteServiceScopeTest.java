package com.example.hms.fhir.write;

import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import com.example.hms.fhir.FhirWriteProperties;
import com.example.hms.fhir.mapper.ObservationFhirMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import org.hl7.fhir.r4.model.Observation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit gap B1 on FHIR PUT /Observation: the tenant predicate is the one
 * REST PUT /lab-results/{id} uses — the ordering hospital and the performing
 * laboratory may amend the result, a third hospital is forbidden.
 */
@ExtendWith(MockitoExtension.class)
class ObservationFhirWriteServiceScopeTest {

    @Mock private ObservationFhirMapper observationMapper;
    @Mock private LabResultRepository labResultRepository;
    @Mock private AuditEventLogService auditEventLogService;

    private ObservationFhirWriteService service;
    private Hospital ordering;
    private Hospital performing;
    private LabResult result;
    private String fhirId;

    @BeforeEach
    void setUp() {
        FhirWriteProperties properties = new FhirWriteProperties();
        properties.setEnabled(true);
        service = new ObservationFhirWriteService(properties, observationMapper, labResultRepository, auditEventLogService);

        ordering = hospital();
        performing = hospital();
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setHospital(ordering);
        order.setPerformingHospital(performing);
        result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(order);
        fhirId = "labresult-" + result.getId();
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    @Test
    void performingLaboratoryMayUpdateTheObservation() {
        scope(performing);
        when(labResultRepository.findById(result.getId())).thenReturn(Optional.of(result));
        when(labResultRepository.save(result)).thenReturn(result);

        assertThat(service.updateLabResult(fhirId, new Observation())).isSameAs(result);
        verify(observationMapper).applyFhirLabResultUpdates(any(), any());
    }

    @Test
    void orderingHospitalMayUpdateTheObservation() {
        scope(ordering);
        when(labResultRepository.findById(result.getId())).thenReturn(Optional.of(result));
        when(labResultRepository.save(result)).thenReturn(result);

        assertThat(service.updateLabResult(fhirId, new Observation())).isSameAs(result);
    }

    @Test
    void thirdHospitalIsForbidden() {
        scope(hospital());
        when(labResultRepository.findById(result.getId())).thenReturn(Optional.of(result));

        Observation observation = new Observation();
        assertThatThrownBy(() -> service.updateLabResult(fhirId, observation))
            .isInstanceOf(ForbiddenOperationException.class);
        verify(labResultRepository, never()).save(any());
    }

    private static void scope(Hospital hospital) {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(hospital.getId())
            .build());
    }

    private static Hospital hospital() {
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        return hospital;
    }
}
