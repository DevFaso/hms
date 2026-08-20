package com.example.hms.service.integration;

import com.example.hms.enums.ActorType;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabSpecimen;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.LabSpecimenRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.integration.impl.MllpInboundLabServiceImpl;
import com.example.hms.service.integration.message.IntegrationMessageRecorder;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MllpInboundLabServiceImplTest {

    @Mock private LabSpecimenRepository specimenRepository;
    @Mock private LabResultRepository labResultRepository;
    @Mock private IntegrationMessageRecorder messageRecorder;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private com.example.hms.service.CriticalValueNotificationService criticalValueNotificationService;

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
    @DisplayName("ACCEPTED — persists LabResult with actorType=SYSTEM, MLLP actorLabel, and composite source key")
    void acceptedHappyPath() {
        when(specimenRepository.findByAccessionNumber("ACC-1")).thenReturn(Optional.of(specimen));
        when(labResultRepository.findFirstBySourceSendingApplicationAndSourceSendingFacilityAndSourceMessageControlId(
                "ROCHE_COBAS", "LAB_A", "MSG-CTRL-1"))
            .thenReturn(Optional.empty());
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));

        MllpInboundOutcome outcome = service.processOruR01(
            observation("ACC-1", "5.4"), hospital, "ROCHE_COBAS", "LAB_A",
            "MSG-CTRL-1", "MSH|...\r");

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
        assertThat(saved.getSourceSendingApplication()).isEqualTo("ROCHE_COBAS");
        assertThat(saved.getSourceSendingFacility()).isEqualTo("LAB_A");
        assertThat(saved.getSourceMessageControlId()).isEqualTo("MSG-CTRL-1");

        verify(messageRecorder).recordMessage(
            eq("MLLP:ROCHE_COBAS/LAB_A"), isNull(),
            eq(IntegrationMessageDirection.INBOUND), eq("ORU^R01"),
            eq("MSH|...\r"), eq(IntegrationMessageStatus.RECEIVED), isNull());

        ArgumentCaptor<AuditEventRequestDTO> auditCaptor =
            ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getEventType()).isEqualTo(AuditEventType.LAB_RESULT_UPDATED);
        assertThat(auditCaptor.getValue().getEntityType()).isEqualTo("LabResult");
    }

    @Test
    @DisplayName("ACCEPTED but no re-insert — same (sender, MSH-10) arrives twice (analyzer retransmit)")
    void replayShortCircuits() {
        LabResult existing = LabResult.builder().build();
        existing.setId(UUID.randomUUID());
        when(labResultRepository.findFirstBySourceSendingApplicationAndSourceSendingFacilityAndSourceMessageControlId(
                "APP", "FAC", "MSG-REPLAY"))
            .thenReturn(Optional.of(existing));

        MllpInboundOutcome outcome = service.processOruR01(
            observation("ACC-1", "5.4"), hospital, "APP", "FAC",
            "MSG-REPLAY", "MSH|...\r");

        assertThat(outcome).isEqualTo(MllpInboundOutcome.ACCEPTED);
        verify(labResultRepository, never()).save(any());
        verify(specimenRepository, never()).findByAccessionNumber(any());
        verify(messageRecorder).recordMessage(
            any(), any(), eq(IntegrationMessageDirection.INBOUND), eq("ORU^R01"),
            any(), eq(IntegrationMessageStatus.RECEIVED), any());
        verify(auditEventLogService, never()).logEvent(any());
    }

    @Test
    @DisplayName("Two different analyzers reusing the same MSH-10 do NOT collapse")
    void differentSendersWithSameControlIdDoNotCollapse() {
        when(specimenRepository.findByAccessionNumber("ACC-1")).thenReturn(Optional.of(specimen));
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));
        // The lookup keyed on (MINDRAY, LAB_A, COMMON-ID) returns empty —
        // a previously-saved row with the same MSH-10 from (SYSMEX, LAB_B,
        // COMMON-ID) is in a different composite-key bucket and must not
        // be returned. The service treats this as a fresh write.
        when(labResultRepository.findFirstBySourceSendingApplicationAndSourceSendingFacilityAndSourceMessageControlId(
                "MINDRAY", "LAB_A", "COMMON-ID"))
            .thenReturn(Optional.empty());

        MllpInboundOutcome outcome = service.processOruR01(
            observation("ACC-1", "5.7"), hospital, "MINDRAY", "LAB_A",
            "COMMON-ID", "MSH|...\r");

        assertThat(outcome).isEqualTo(MllpInboundOutcome.ACCEPTED);
        verify(labResultRepository).save(any(LabResult.class));
    }

    @Test
    @DisplayName("REJECTED_NOT_FOUND — accession number does not match any specimen")
    void rejectedWhenAccessionUnknown() {
        when(specimenRepository.findByAccessionNumber("ACC-MISSING")).thenReturn(Optional.empty());

        MllpInboundOutcome outcome = service.processOruR01(
            observation("ACC-MISSING", "5.4"), hospital, "APP", "FAC",
            "MSG-CTRL-2", "MSH|...\r");

        assertThat(outcome).isEqualTo(MllpInboundOutcome.REJECTED_NOT_FOUND);
        verify(labResultRepository, never()).save(any());
        verify(messageRecorder).recordMessage(
            any(), any(), any(), any(), any(),
            eq(IntegrationMessageStatus.FAILED), any());
    }

    @Test
    @DisplayName("REJECTED_CROSS_TENANT — order belongs to a different hospital than the sender")
    void rejectedWhenCrossTenant() {
        Hospital otherHospital = new Hospital();
        otherHospital.setId(UUID.randomUUID());
        labOrder.setHospital(otherHospital);
        when(specimenRepository.findByAccessionNumber("ACC-1")).thenReturn(Optional.of(specimen));

        MllpInboundOutcome outcome = service.processOruR01(
            observation("ACC-1", "5.4"), hospital, "APP", "FAC",
            "MSG-CTRL-3", "MSH|...\r");

        assertThat(outcome).isEqualTo(MllpInboundOutcome.REJECTED_CROSS_TENANT);
        verify(labResultRepository, never()).save(any());
        verify(messageRecorder).recordMessage(
            any(), any(), any(), any(), any(),
            eq(IntegrationMessageStatus.FAILED), any());
    }

    @Test
    @DisplayName("REJECTED_INVALID — missing OBR-2 placer order number")
    void rejectedInvalidWhenPlacerMissing() {
        ParsedObservation obs = new ParsedObservation(
            "p", "", "f", "GLU", "5.4", "mmol/L", "N", LocalDateTime.now());

        assertThat(service.processOruR01(obs, hospital, "APP", "FAC", "MSG-CTRL-4", "MSH|...\r"))
            .isEqualTo(MllpInboundOutcome.REJECTED_INVALID);
        verify(labResultRepository, never()).save(any());
    }

    @Test
    @DisplayName("REJECTED_INVALID — missing OBX result value")
    void rejectedInvalidWhenResultValueBlank() {
        ParsedObservation obs = new ParsedObservation(
            "p", "ACC-1", "f", "GLU", "", "mmol/L", "N", LocalDateTime.now());

        assertThat(service.processOruR01(obs, hospital, "APP", "FAC", "MSG-CTRL-5", "MSH|...\r"))
            .isEqualTo(MllpInboundOutcome.REJECTED_INVALID);
    }

    @Test
    @DisplayName("REJECTED_INVALID — null hospital")
    void rejectedInvalidWhenHospitalNull() {
        assertThat(service.processOruR01(observation("ACC-1", "5.4"), null,
            "APP", "FAC", "MSG-CTRL-6", "MSH|...\r"))
            .isEqualTo(MllpInboundOutcome.REJECTED_INVALID);
    }

    @Test
    @DisplayName("REJECTED_INVALID — specimen has no labOrder.hospital")
    void rejectedInvalidWhenOrderHospitalNull() {
        labOrder.setHospital(null);
        when(specimenRepository.findByAccessionNumber("ACC-1")).thenReturn(Optional.of(specimen));

        assertThat(service.processOruR01(observation("ACC-1", "5.4"), hospital,
            "APP", "FAC", "MSG-CTRL-7", "MSH|...\r"))
            .isEqualTo(MllpInboundOutcome.REJECTED_INVALID);
    }

    @Test
    @DisplayName("Null MSH-10 still processed normally (analyzer doesn't always emit one)")
    void nullMessageControlIdStillProcessed() {
        when(specimenRepository.findByAccessionNumber("ACC-1")).thenReturn(Optional.of(specimen));
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));

        MllpInboundOutcome outcome = service.processOruR01(
            observation("ACC-1", "5.4"), hospital, "APP", "FAC",
            null, "MSH|...\r");

        assertThat(outcome).isEqualTo(MllpInboundOutcome.ACCEPTED);
        verify(labResultRepository, never())
            .findFirstBySourceSendingApplicationAndSourceSendingFacilityAndSourceMessageControlId(
                any(), any(), any());
        ArgumentCaptor<LabResult> captor = ArgumentCaptor.forClass(LabResult.class);
        verify(labResultRepository).save(captor.capture());
        assertThat(captor.getValue().getSourceMessageControlId()).isNull();
        assertThat(captor.getValue().getSourceSendingApplication()).isEqualTo("APP");
        assertThat(captor.getValue().getSourceSendingFacility()).isEqualTo("FAC");
    }
}
