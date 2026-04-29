package com.example.hms.service.integration;

import com.example.hms.enums.ActorType;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabSpecimen;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.LabSpecimenRepository;
import com.example.hms.service.integration.impl.MllpInboundLabServiceImpl;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedObservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MllpInboundLabServiceImplTest {

    @Mock private LabSpecimenRepository specimenRepository;
    @Mock private LabResultRepository labResultRepository;

    @InjectMocks private MllpInboundLabServiceImpl service;

    private Hospital hospital;
    private LabOrder labOrder;
    private LabSpecimen specimen;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());

        labOrder = new LabOrder();
        labOrder.setId(UUID.randomUUID());
        labOrder.setHospital(hospital);

        specimen = new LabSpecimen();
        specimen.setId(UUID.randomUUID());
        specimen.setLabOrder(labOrder);
    }

    private ParsedObservation observation(String placer, String value) {
        return new ParsedObservation(
            "patient-mrn", placer, "filler-1", "GLU", value, "mmol/L", "N",
            LocalDateTime.of(2026, 4, 29, 8, 30));
    }

    @Test
    @DisplayName("ACCEPTED — persists LabResult with actorType=SYSTEM and the MLLP actorLabel")
    void acceptedHappyPath() {
        when(specimenRepository.findByAccessionNumber("ACC-1")).thenReturn(Optional.of(specimen));
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));

        MllpInboundOutcome outcome = service.processOruR01(
            observation("ACC-1", "5.4"), hospital, "ROCHE_COBAS", "LAB_A");

        assertThat(outcome).isEqualTo(MllpInboundOutcome.ACCEPTED);

        ArgumentCaptor<LabResult> captor = ArgumentCaptor.forClass(LabResult.class);
        verify(labResultRepository).save(captor.capture());
        LabResult saved = captor.getValue();
        assertThat(saved.getLabOrder()).isSameAs(labOrder);
        assertThat(saved.getAssignment()).isNull();
        assertThat(saved.getActorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(saved.getActorLabel()).isEqualTo("MLLP:ROCHE_COBAS/LAB_A");
        assertThat(saved.getResultValue()).isEqualTo("5.4");
        assertThat(saved.getResultUnit()).isEqualTo("mmol/L");
    }

    @Test
    @DisplayName("REJECTED_NOT_FOUND — accession number does not match any specimen")
    void rejectedWhenAccessionUnknown() {
        when(specimenRepository.findByAccessionNumber("ACC-MISSING")).thenReturn(Optional.empty());

        MllpInboundOutcome outcome = service.processOruR01(
            observation("ACC-MISSING", "5.4"), hospital, "APP", "FAC");

        assertThat(outcome).isEqualTo(MllpInboundOutcome.REJECTED_NOT_FOUND);
        verify(labResultRepository, never()).save(any());
    }

    @Test
    @DisplayName("REJECTED_CROSS_TENANT — order belongs to a different hospital than the sender")
    void rejectedWhenCrossTenant() {
        Hospital otherHospital = new Hospital();
        otherHospital.setId(UUID.randomUUID());
        labOrder.setHospital(otherHospital);
        when(specimenRepository.findByAccessionNumber("ACC-1")).thenReturn(Optional.of(specimen));

        MllpInboundOutcome outcome = service.processOruR01(
            observation("ACC-1", "5.4"), hospital, "APP", "FAC");

        assertThat(outcome).isEqualTo(MllpInboundOutcome.REJECTED_CROSS_TENANT);
        verify(labResultRepository, never()).save(any());
    }

    @Test
    @DisplayName("REJECTED_INVALID — missing OBR-2 placer order number")
    void rejectedInvalidWhenPlacerMissing() {
        ParsedObservation obs = new ParsedObservation(
            "p", "", "f", "GLU", "5.4", "mmol/L", "N", LocalDateTime.now());

        assertThat(service.processOruR01(obs, hospital, "APP", "FAC"))
            .isEqualTo(MllpInboundOutcome.REJECTED_INVALID);
        verify(labResultRepository, never()).save(any());
    }

    @Test
    @DisplayName("REJECTED_INVALID — missing OBX result value")
    void rejectedInvalidWhenResultValueBlank() {
        ParsedObservation obs = new ParsedObservation(
            "p", "ACC-1", "f", "GLU", "", "mmol/L", "N", LocalDateTime.now());

        assertThat(service.processOruR01(obs, hospital, "APP", "FAC"))
            .isEqualTo(MllpInboundOutcome.REJECTED_INVALID);
    }

    @Test
    @DisplayName("REJECTED_INVALID — null hospital")
    void rejectedInvalidWhenHospitalNull() {
        assertThat(service.processOruR01(observation("ACC-1", "5.4"), null, "APP", "FAC"))
            .isEqualTo(MllpInboundOutcome.REJECTED_INVALID);
    }

    @Test
    @DisplayName("REJECTED_INVALID — specimen has no labOrder.hospital")
    void rejectedInvalidWhenOrderHospitalNull() {
        labOrder.setHospital(null);
        when(specimenRepository.findByAccessionNumber("ACC-1")).thenReturn(Optional.of(specimen));

        assertThat(service.processOruR01(observation("ACC-1", "5.4"), hospital, "APP", "FAC"))
            .isEqualTo(MllpInboundOutcome.REJECTED_INVALID);
    }
}
