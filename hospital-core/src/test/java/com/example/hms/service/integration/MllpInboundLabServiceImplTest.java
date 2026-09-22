package com.example.hms.service.integration;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.enums.ActorType;
import com.example.hms.enums.LabOrderStatus;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        return observation(placer, value, "1", "GLU", "N");
    }

    private ParsedObservation observation(String placer, String value, String setId,
                                          String testCode, String abnormalFlag) {
        return new ParsedObservation(
            "patient-mrn", placer, "filler-1", setId, testCode, value, "mmol/L",
            "3.9-6.1", abnormalFlag, LocalDateTime.of(2026, 4, 29, 8, 30));
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
            List.of(observation("ACC-1", "5.4")), hospital, "ROCHE_COBAS", "LAB_A",
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
        assertThat(saved.getSourceObservationSetId()).isEqualTo("1");
        assertThat(saved.getTestCode()).isEqualTo("GLU");
        assertThat(saved.getReferenceRange()).isEqualTo("3.9-6.1");

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
            List.of(observation("ACC-1", "5.4")), hospital, "APP", "FAC",
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
            List.of(observation("ACC-1", "5.7")), hospital, "MINDRAY", "LAB_A",
            "COMMON-ID", "MSH|...\r");

        assertThat(outcome).isEqualTo(MllpInboundOutcome.ACCEPTED);
        verify(labResultRepository).save(any(LabResult.class));
    }

    @Test
    @DisplayName("REJECTED_NOT_FOUND — accession number does not match any specimen")
    void rejectedWhenAccessionUnknown() {
        when(specimenRepository.findByAccessionNumber("ACC-MISSING")).thenReturn(Optional.empty());

        MllpInboundOutcome outcome = service.processOruR01(
            List.of(observation("ACC-MISSING", "5.4")), hospital, "APP", "FAC",
            "MSG-CTRL-2", "MSH|...\r");

        assertThat(outcome).isEqualTo(MllpInboundOutcome.REJECTED_NOT_FOUND);
        verify(labResultRepository, never()).save(any());
        verify(messageRecorder).recordMessage(
            any(), any(), any(), any(), any(),
            eq(IntegrationMessageStatus.FAILED), any());
    }

    @Test
    @DisplayName("B13 — an order of another hospital is rejected exactly like an unknown accession; only the internal row says why")
    void rejectedWhenCrossTenant() {
        Hospital otherHospital = new Hospital();
        otherHospital.setId(UUID.randomUUID());
        labOrder.setHospital(otherHospital);
        when(specimenRepository.findByAccessionNumber("ACC-1")).thenReturn(Optional.of(specimen));
        when(specimenRepository.findByAccessionNumber("ACC-MISSING")).thenReturn(Optional.empty());

        MllpInboundOutcome crossTenant = service.processOruR01(
            List.of(observation("ACC-1", "5.4")), hospital, "APP", "FAC",
            "MSG-CTRL-3", "MSH|...\r");
        MllpInboundOutcome unknown = service.processOruR01(
            List.of(observation("ACC-MISSING", "5.4")), hospital, "APP", "FAC",
            "MSG-CTRL-3b", "MSH|...\r");

        // The sender cannot tell "exists elsewhere" from "does not exist".
        assertThat(crossTenant).isEqualTo(unknown).isEqualTo(MllpInboundOutcome.REJECTED_NOT_FOUND);
        verify(labResultRepository, never()).save(any());
        // The operator still can: the integration-message row keeps the reason.
        verify(messageRecorder).recordMessage(
            any(), any(), any(), any(), any(),
            eq(IntegrationMessageStatus.FAILED), eq("cross-tenant rejection"));
        verify(messageRecorder).recordMessage(
            any(), any(), any(), any(), any(),
            eq(IntegrationMessageStatus.FAILED), eq("accession ACC-MISSING not found"));
    }

    // ── B14 — release + order status on ingest ───────────────────────────

    @Test
    @DisplayName("B14 — auto-release off (default): the row lands unreleased and the order is RESULTED")
    void ingestedRowStaysUnreleasedByDefault() {
        when(specimenRepository.findByAccessionNumber("ACC-1")).thenReturn(Optional.of(specimen));
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));
        labOrder.setStatus(LabOrderStatus.ORDERED);

        service.processOruR01(List.of(observation("ACC-1", "5.4")), hospital, "APP", "FAC", null, "MSH|...\r");

        ArgumentCaptor<LabResult> captor = ArgumentCaptor.forClass(LabResult.class);
        verify(labResultRepository).save(captor.capture());
        assertThat(captor.getValue().isReleased()).isFalse();
        assertThat(captor.getValue().getReleasedAt()).isNull();
        assertThat(labOrder.getStatus()).isEqualTo(LabOrderStatus.RESULTED);
    }

    @Test
    @DisplayName("B14 — auto-release on: a NORMAL observation is released as Autoverification, an abnormal one is not")
    void autoReleaseReleasesOnlyNormalRows() {
        ReflectionTestUtils.setField(service, "autoReleaseEnabled", true);
        when(specimenRepository.findByAccessionNumber("ACC-1")).thenReturn(Optional.of(specimen));
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));

        service.processOruR01(List.of(
                observation("ACC-1", "5.4", "1", "GLU", "N"),
                observation("ACC-1", "9.9", "2", "GLU2", "H"),
                observation("ACC-1", "1.1", "3", "K", "LL")),
            hospital, "APP", "FAC", null, "MSH|...\r");

        ArgumentCaptor<LabResult> captor = ArgumentCaptor.forClass(LabResult.class);
        verify(labResultRepository, times(3)).save(captor.capture());
        List<LabResult> saved = captor.getAllValues();
        assertThat(saved.get(0).isReleased()).isTrue();
        assertThat(saved.get(0).getReleasedAt()).isNotNull();
        assertThat(saved.get(0).getReleasedByDisplay()).isEqualTo("Autoverification");
        assertThat(saved.get(1).isReleased()).isFalse();
        assertThat(saved.get(2).isReleased()).isFalse();
    }

    @Test
    @DisplayName("B14 — the status advance is guarded: pre-result states move to RESULTED, later and cancelled ones stay")
    void orderStatusAdvanceIsGuarded() {
        when(specimenRepository.findByAccessionNumber("ACC-1")).thenReturn(Optional.of(specimen));
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));

        for (LabOrderStatus before : List.of(LabOrderStatus.ORDERED, LabOrderStatus.PENDING,
                LabOrderStatus.COLLECTED, LabOrderStatus.RECEIVED, LabOrderStatus.IN_PROGRESS)) {
            labOrder.setStatus(before);
            service.processOruR01(List.of(observation("ACC-1", "5.4")), hospital, "APP", "FAC", null, "MSH|...\r");
            assertThat(labOrder.getStatus()).as("from %s", before).isEqualTo(LabOrderStatus.RESULTED);
        }
        for (LabOrderStatus untouched : List.of(LabOrderStatus.RESULTED, LabOrderStatus.VERIFIED,
                LabOrderStatus.COMPLETED, LabOrderStatus.CANCELLED)) {
            labOrder.setStatus(untouched);
            service.processOruR01(List.of(observation("ACC-1", "5.4")), hospital, "APP", "FAC", null, "MSH|...\r");
            assertThat(labOrder.getStatus()).as("from %s", untouched).isEqualTo(untouched);
        }
    }

    // ── B18 — OBX-8 direction ────────────────────────────────────────────

    @Test
    @DisplayName("B18 — L/H keep their direction, LL/HH/A map to the family the consumers key on")
    void abnormalFlagKeepsDirection() {
        when(specimenRepository.findByAccessionNumber("ACC-1")).thenReturn(Optional.of(specimen));
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));

        service.processOruR01(List.of(
                observation("ACC-1", "1", "1", "T1", "L"),
                observation("ACC-1", "2", "2", "T2", "H"),
                observation("ACC-1", "3", "3", "T3", "LL"),
                observation("ACC-1", "4", "4", "T4", "HH"),
                observation("ACC-1", "5", "5", "T5", "A"),
                observation("ACC-1", "6", "6", "T6", "N"),
                observation("ACC-1", "7", "7", "T7", "zz")),
            hospital, "APP", "FAC", null, "MSH|...\r");

        ArgumentCaptor<LabResult> captor = ArgumentCaptor.forClass(LabResult.class);
        verify(labResultRepository, times(7)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(LabResult::getAbnormalFlag).containsExactly(
            AbnormalFlag.ABNORMAL_LOW, AbnormalFlag.ABNORMAL_HIGH,
            AbnormalFlag.CRITICAL, AbnormalFlag.CRITICAL,
            AbnormalFlag.ABNORMAL, AbnormalFlag.NORMAL, AbnormalFlag.NORMAL);
    }

    @Test
    @DisplayName("REJECTED_INVALID — missing OBR-2 placer order number")
    void rejectedInvalidWhenPlacerMissing() {
        ParsedObservation obs = new ParsedObservation(
            "p", "", "f", "1", "GLU", "5.4", "mmol/L", "", "N", LocalDateTime.now());

        assertThat(service.processOruR01(List.of(obs), hospital, "APP", "FAC", "MSG-CTRL-4", "MSH|...\r"))
            .isEqualTo(MllpInboundOutcome.REJECTED_INVALID);
        verify(labResultRepository, never()).save(any());
    }

    @Test
    @DisplayName("REJECTED_INVALID — missing OBX result value")
    void rejectedInvalidWhenResultValueBlank() {
        ParsedObservation obs = new ParsedObservation(
            "p", "ACC-1", "f", "1", "GLU", "", "mmol/L", "", "N", LocalDateTime.now());

        assertThat(service.processOruR01(List.of(obs), hospital, "APP", "FAC", "MSG-CTRL-5", "MSH|...\r"))
            .isEqualTo(MllpInboundOutcome.REJECTED_INVALID);
    }

    @Test
    @DisplayName("REJECTED_INVALID — null hospital")
    void rejectedInvalidWhenHospitalNull() {
        assertThat(service.processOruR01(List.of(observation("ACC-1", "5.4")), null,
            "APP", "FAC", "MSG-CTRL-6", "MSH|...\r"))
            .isEqualTo(MllpInboundOutcome.REJECTED_INVALID);
    }

    @Test
    @DisplayName("REJECTED_INVALID — specimen has no labOrder.hospital")
    void rejectedInvalidWhenOrderHospitalNull() {
        labOrder.setHospital(null);
        when(specimenRepository.findByAccessionNumber("ACC-1")).thenReturn(Optional.of(specimen));

        assertThat(service.processOruR01(List.of(observation("ACC-1", "5.4")), hospital,
            "APP", "FAC", "MSG-CTRL-7", "MSH|...\r"))
            .isEqualTo(MllpInboundOutcome.REJECTED_INVALID);
    }

    @Test
    @DisplayName("Null MSH-10 still processed normally (analyzer doesn't always emit one)")
    void nullMessageControlIdStillProcessed() {
        when(specimenRepository.findByAccessionNumber("ACC-1")).thenReturn(Optional.of(specimen));
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));

        MllpInboundOutcome outcome = service.processOruR01(
            List.of(observation("ACC-1", "5.4")), hospital, "APP", "FAC",
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

    // ── Multi-OBX fan-out (V131) ─────────────────────────────────────────

    @Test
    @DisplayName("Multi-OBX — every observation persists as its own row, criticals notify per row")
    void multiObxPersistsEveryObservation() {
        when(specimenRepository.findByAccessionNumber("ACC-1")).thenReturn(Optional.of(specimen));
        when(labResultRepository.findFirstBySourceSendingApplicationAndSourceSendingFacilityAndSourceMessageControlId(
                "APP", "FAC", "MSG-MULTI"))
            .thenReturn(Optional.empty());
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));

        // A 3-analyte CBC with the critical on OBX-2 — exactly the shape
        // the old first-OBX-only parser silently truncated.
        List<ParsedObservation> panel = List.of(
            observation("ACC-1", "6.2", "1", "WBC", "N"),
            observation("ACC-1", "6.6", "2", "HGB", "LL"),
            observation("ACC-1", "150", "3", "PLT", "N"));

        MllpInboundOutcome outcome = service.processOruR01(
            panel, hospital, "APP", "FAC", "MSG-MULTI", "MSH|...\r");

        assertThat(outcome).isEqualTo(MllpInboundOutcome.ACCEPTED);
        ArgumentCaptor<LabResult> captor = ArgumentCaptor.forClass(LabResult.class);
        verify(labResultRepository, times(3)).save(captor.capture());
        List<LabResult> saved = captor.getAllValues();
        assertThat(saved).extracting(LabResult::getSourceObservationSetId)
            .containsExactly("1", "2", "3");
        assertThat(saved).extracting(LabResult::getTestCode)
            .containsExactly("WBC", "HGB", "PLT");
        assertThat(saved).extracting(LabResult::getSourceMessageControlId)
            .containsOnly("MSG-MULTI");
        // The critical on OBX-2 must notify even though OBX-1 was normal.
        verify(criticalValueNotificationService, times(3)).notifyIfCritical(any(LabResult.class));
        // One integration-message row for the whole message, not one per OBX.
        verify(messageRecorder).recordMessage(
            any(), any(), eq(IntegrationMessageDirection.INBOUND), eq("ORU^R01"),
            any(), eq(IntegrationMessageStatus.RECEIVED), isNull());
    }

    @Test
    @DisplayName("Blank or duplicate OBX-1 set ids fall back to the 1-based position for ALL rows")
    void setIdFallsBackToPositionWhenBlankOrDuplicate() {
        when(specimenRepository.findByAccessionNumber("ACC-1")).thenReturn(Optional.of(specimen));
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));

        List<ParsedObservation> panel = List.of(
            observation("ACC-1", "5.4", "7", "GLU", "N"),
            observation("ACC-1", "3.1", "7", "UREA", "N"));

        MllpInboundOutcome outcome = service.processOruR01(
            panel, hospital, "APP", "FAC", null, "MSH|...\r");

        assertThat(outcome).isEqualTo(MllpInboundOutcome.ACCEPTED);
        ArgumentCaptor<LabResult> captor = ArgumentCaptor.forClass(LabResult.class);
        verify(labResultRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(LabResult::getSourceObservationSetId)
            .containsExactly("1", "2");
    }

    @Test
    @DisplayName("One malformed OBX rejects the WHOLE message — no partial persist")
    void oneBadObxRejectsWholeMessage() {
        List<ParsedObservation> panel = List.of(
            observation("ACC-1", "5.4"),
            observation("ACC-1", ""));

        assertThat(service.processOruR01(panel, hospital, "APP", "FAC", "MSG-PART", "MSH|...\r"))
            .isEqualTo(MllpInboundOutcome.REJECTED_INVALID);
        verify(labResultRepository, never()).save(any());
        verify(criticalValueNotificationService, never()).notifyIfCritical(any());
    }

    @Test
    @DisplayName("REJECTED_INVALID — no OBX segments at all")
    void emptyObservationListRejected() {
        assertThat(service.processOruR01(List.of(), hospital, "APP", "FAC", "MSG-EMPTY", "MSH|...\r"))
            .isEqualTo(MllpInboundOutcome.REJECTED_INVALID);
        verify(labResultRepository, never()).save(any());
    }
}
