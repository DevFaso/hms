package com.example.hms.fhir.write;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit gap B1 on FHIR PUT /Observation: the tenant predicate is the one
 * REST PUT /lab-results/{id} uses — the ordering hospital and the performing
 * laboratory may amend the result; a third hospital gets exactly the answer a
 * nonexistent result gets.
 */
@ExtendWith(MockitoExtension.class)
class ObservationFhirWriteServiceScopeTest {

    private static final FhirContext FHIR = FhirContext.forR4();

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
    void thirdHospitalIsByteIdenticalToAMissingResult() {
        // The same id asked in two worlds: a real result another hospital
        // handles, and no result at all. LabResult is not TenantScoped, so
        // findById reaches every tenant; a 403 for the first beside a 404 for
        // the second let a writer enumerate which lab results exist elsewhere.
        scope(hospital());

        when(labResultRepository.findById(result.getId())).thenReturn(Optional.of(result));
        Throwable elsewhere = catchThrowable(() -> service.updateLabResult(fhirId, new Observation()));

        when(labResultRepository.findById(result.getId())).thenReturn(Optional.empty());
        Throwable nowhere = catchThrowable(() -> service.updateLabResult(fhirId, new Observation()));

        assertThat(nowhere).isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThat(elsewhere).isExactlyInstanceOf(ResourceNotFoundException.class);
        BaseServerResponseException a = (BaseServerResponseException) elsewhere;
        BaseServerResponseException b = (BaseServerResponseException) nowhere;
        assertThat(a.getStatusCode()).isEqualTo(b.getStatusCode());
        assertThat(a.getMessage()).isEqualTo(b.getMessage());
        assertThat(FHIR.newJsonParser().encodeResourceToString(a.getOperationOutcome()))
            .isEqualTo(FHIR.newJsonParser().encodeResourceToString(b.getOperationOutcome()));
        verify(observationMapper, never()).applyFhirLabResultUpdates(any(), any());
        verify(labResultRepository, never()).save(any());
    }

    @Test
    void noHospitalScopeIsForbidden() {
        // "Pin a hospital" names no identifier, so it may differ from a 404.
        HospitalContextHolder.setContext(HospitalContext.builder().build());
        Observation observation = new Observation();

        assertThatThrownBy(() -> service.updateLabResult(fhirId, observation))
            .isInstanceOf(ForbiddenOperationException.class);
        verify(labResultRepository, never()).findById(any());
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
