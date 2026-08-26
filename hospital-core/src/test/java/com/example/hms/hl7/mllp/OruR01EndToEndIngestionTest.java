package com.example.hms.hl7.mllp;

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
import com.example.hms.service.integration.MllpInboundAdtService;
import com.example.hms.service.integration.MllpInboundMergeService;
import com.example.hms.service.integration.impl.MllpInboundLabServiceImpl;
import com.example.hms.service.integration.message.IntegrationMessageRecorder;
import com.example.hms.service.platform.MllpAllowedSenderService;
import com.example.hms.utility.Hl7v2MessageBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end ingestion test where the dispatcher routes a realistic
 * vendor ORU^R01 sample through the **real** {@link MllpInboundLabServiceImpl}
 * — only the repositories, audit log, integration recorder, and ADT
 * service are mocked. This complements {@link OruR01VendorSampleIngestionTest},
 * which mocks the inbound lab service itself and therefore would not
 * catch a regression in the service's idempotency / persistence /
 * audit / recorder wiring (Copilot review feedback on roadmap row 23).
 *
 * <p>The contract under test here:
 * <ol>
 *   <li>Dispatcher parses MSH + ORU body and calls the real service
 *       with the message control id and raw body.</li>
 *   <li>Service looks up the composite idempotency key
 *       (sendingApp, sendingFacility, MSH-10) — empty → fresh write.</li>
 *   <li>Service persists a LabResult with the SYSTEM actor, the MLLP
 *       label, the abnormal flag mapped from OBX-8, and all three
 *       source columns populated.</li>
 *   <li>Service emits the IntegrationMessageRecorder RECEIVED row and
 *       the LAB_RESULT_UPDATED audit event.</li>
 *   <li>A second dispatch of the same raw message — analyzer
 *       retransmit — hits the composite-key index and ACCEPTs without
 *       a second {@code save}.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class OruR01EndToEndIngestionTest {

    @Mock private MllpAllowedSenderService allowlist;
    @Mock private MllpInboundAdtService inboundAdt;
    @Mock private MllpInboundMergeService inboundMerge;
    @Mock private LabSpecimenRepository specimenRepository;
    @Mock private LabResultRepository labResultRepository;
    @Mock private IntegrationMessageRecorder messageRecorder;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private com.example.hms.service.CriticalValueNotificationService criticalValueNotificationService;

    private Hl7MessageDispatcher dispatcher;
    private Hospital hospital;
    private LabOrder labOrder;
    private LabSpecimen specimen;

    @BeforeEach
    void setUp() {
        // Real service, real parser; mocked I/O collaborators only.
        MllpInboundLabServiceImpl labService = new MllpInboundLabServiceImpl(
            specimenRepository, labResultRepository, messageRecorder, auditEventLogService,
            criticalValueNotificationService);
        dispatcher = new Hl7MessageDispatcher(
            new Hl7v2MessageBuilder(), allowlist, labService, inboundAdt, inboundMerge, messageRecorder);

        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("Hopital Yalgado Ouedraogo");

        labOrder = new LabOrder();
        labOrder.setId(UUID.randomUUID());
        labOrder.setHospital(hospital);

        specimen = new LabSpecimen();
        specimen.setId(UUID.randomUUID());
        specimen.setLabOrder(labOrder);
    }

    @Test
    @DisplayName("Mindray BS-240 glucose end-to-end → real service persists composite source columns and emits audit")
    void mindrayPersistsAndAudits() {
        String mindray = String.join("\r",
            "MSH|^~\\&|MINDRAY^BS-240^L|LAB-A^FACILITY-1^L|HMS|HOSP-OUAGA|"
                + "20260515101545||ORU^R01|MINDRAY-2026-00417|P|2.5.1",
            "PID|1||MRN-0042^^^HMS^MR||DIALLO^AMINATA||19720504|F",
            "OBR|1|ACC-2026-00417|24f3a^MINDRAY^L|15074-8^GLUCOSE^LN|||"
                + "20260515101200|||||||||||||||20260515101545|||F",
            "OBX|1|NM|15074-8^GLUCOSE^LN||5.7|mmol/L|3.9-5.6|H|||F|||"
                + "20260515101200",
            "") + "\r";

        when(allowlist.resolveHospital("MINDRAY^BS-240^L", "LAB-A^FACILITY-1^L"))
            .thenReturn(Optional.of(hospital));
        // Composite-key lookup returns empty — fresh write.
        when(labResultRepository
            .findFirstBySourceSendingApplicationAndSourceSendingFacilityAndSourceMessageControlId(
                "MINDRAY^BS-240^L", "LAB-A^FACILITY-1^L", "MINDRAY-2026-00417"))
            .thenReturn(Optional.empty());
        when(specimenRepository.findByAccessionNumber("ACC-2026-00417"))
            .thenReturn(Optional.of(specimen));
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));

        String ack = dispatcher.dispatch(mindray, "10.20.30.40:5012");

        assertThat(ack).contains("MSA|AA|MINDRAY-2026-00417");

        // The real service composed the LabResult — assert source columns,
        // actor, and the value mapping (OBX H → ABNORMAL, not NORMAL).
        ArgumentCaptor<LabResult> labCap = ArgumentCaptor.forClass(LabResult.class);
        verify(labResultRepository).save(labCap.capture());
        LabResult saved = labCap.getValue();
        assertThat(saved.getLabOrder()).isSameAs(labOrder);
        assertThat(saved.getActorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(saved.getActorLabel()).isEqualTo("MLLP:MINDRAY^BS-240^L/LAB-A^FACILITY-1^L");
        assertThat(saved.getResultValue()).isEqualTo("5.7");
        assertThat(saved.getResultUnit()).isEqualTo("mmol/L");
        assertThat(saved.getSourceSendingApplication()).isEqualTo("MINDRAY^BS-240^L");
        assertThat(saved.getSourceSendingFacility()).isEqualTo("LAB-A^FACILITY-1^L");
        assertThat(saved.getSourceMessageControlId()).isEqualTo("MINDRAY-2026-00417");
        // V131 analyte metadata + per-OBX discriminator
        assertThat(saved.getSourceObservationSetId()).isEqualTo("1");
        assertThat(saved.getTestCode()).isEqualTo("15074-8");
        assertThat(saved.getReferenceRange()).isEqualTo("3.9-5.6");
        // OBX-8 "H" → AbnormalFlag.ABNORMAL (high but not critical)
        assertThat(saved.getAbnormalFlag().name()).isEqualTo("ABNORMAL");

        // Recorder RECEIVED + audit emitted exactly once.
        verify(messageRecorder).recordMessage(
            eq("MLLP:MINDRAY^BS-240^L/LAB-A^FACILITY-1^L"), any(),
            eq(IntegrationMessageDirection.INBOUND), eq("ORU^R01"),
            eq(mindray), eq(IntegrationMessageStatus.RECEIVED), any());

        ArgumentCaptor<AuditEventRequestDTO> auditCap = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(auditCap.capture());
        assertThat(auditCap.getValue().getEventType()).isEqualTo(AuditEventType.LAB_RESULT_UPDATED);
        assertThat(auditCap.getValue().getEntityType()).isEqualTo("LabResult");
    }

    @Test
    @DisplayName("Same analyzer retransmits same MSH-10 → ACCEPTED via composite-key short-circuit, no second save")
    void retransmitShortCircuitsWithRealService() {
        String mindray = String.join("\r",
            "MSH|^~\\&|MINDRAY^BS-240^L|LAB-A^FACILITY-1^L|HMS|HOSP-OUAGA|"
                + "20260515101545||ORU^R01|MINDRAY-2026-REPLAY|P|2.5.1",
            "PID|1||MRN-0042",
            "OBR|1|ACC-2026-00417||15074-8^GLUCOSE^LN|||20260515101200",
            "OBX|1|NM|15074-8^GLUCOSE^LN||5.7|mmol/L|3.9-5.6|H",
            "") + "\r";

        LabResult existing = LabResult.builder().build();
        existing.setId(UUID.randomUUID());

        when(allowlist.resolveHospital("MINDRAY^BS-240^L", "LAB-A^FACILITY-1^L"))
            .thenReturn(Optional.of(hospital));
        // First dispatch: lookup returns empty, fresh write happens.
        // Second dispatch: lookup returns the saved row → short-circuit.
        when(labResultRepository
            .findFirstBySourceSendingApplicationAndSourceSendingFacilityAndSourceMessageControlId(
                "MINDRAY^BS-240^L", "LAB-A^FACILITY-1^L", "MINDRAY-2026-REPLAY"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(existing));
        when(specimenRepository.findByAccessionNumber("ACC-2026-00417"))
            .thenReturn(Optional.of(specimen));
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));

        String ackFirst  = dispatcher.dispatch(mindray, "10.20.30.40:5012");
        String ackSecond = dispatcher.dispatch(mindray, "10.20.30.40:5013");

        assertThat(ackFirst).contains("MSA|AA|MINDRAY-2026-REPLAY");
        assertThat(ackSecond).contains("MSA|AA|MINDRAY-2026-REPLAY");

        // save was called exactly once across two dispatches — the
        // second one short-circuited at the composite-key lookup.
        verify(labResultRepository, times(1)).save(any(LabResult.class));
        // The specimen lookup happened only on the first dispatch; the
        // replay path returns ACCEPTED before reaching the specimen
        // resolution step.
        verify(specimenRepository, times(1)).findByAccessionNumber(anyString());
        // Audit fired only for the fresh write, not the replay.
        verify(auditEventLogService, times(1)).logEvent(any(AuditEventRequestDTO.class));
    }

    @Test
    @DisplayName("Two different analyzers emit the same MSH-10 → both rows persist (composite scope, not global)")
    void differentSendersDoNotCollideOnSharedControlId() {
        String mindray = "MSH|^~\\&|MINDRAY|LAB-A|HMS|HOSP|20260515||ORU^R01|SHARED-ID|P|2.5\r"
                       + "PID|1||p\rOBR|1|ACC-MINDRAY||GLU|||20260515\r"
                       + "OBX|1|NM|GLU||5.5|mmol/L\r";
        String sysmex  = "MSH|^~\\&|SYSMEX|LAB-B|HMS|HOSP|20260515||ORU^R01|SHARED-ID|P|2.5\r"
                       + "PID|1||p\rOBR|1|ACC-SYSMEX||HGB|||20260515\r"
                       + "OBX|1|NM|HGB||14.0|g/dL\r";

        when(allowlist.resolveHospital("MINDRAY", "LAB-A")).thenReturn(Optional.of(hospital));
        when(allowlist.resolveHospital("SYSMEX",  "LAB-B")).thenReturn(Optional.of(hospital));

        // Composite-key lookup is keyed on all three columns — different
        // (sender app, sender facility) means different buckets, so
        // both lookups return empty and both messages persist.
        when(labResultRepository
            .findFirstBySourceSendingApplicationAndSourceSendingFacilityAndSourceMessageControlId(
                anyString(), anyString(), eq("SHARED-ID")))
            .thenReturn(Optional.empty());

        LabSpecimen mindraySpec = new LabSpecimen();
        mindraySpec.setId(UUID.randomUUID()); mindraySpec.setLabOrder(labOrder);
        LabSpecimen sysmexSpec = new LabSpecimen();
        sysmexSpec.setId(UUID.randomUUID()); sysmexSpec.setLabOrder(labOrder);
        when(specimenRepository.findByAccessionNumber("ACC-MINDRAY")).thenReturn(Optional.of(mindraySpec));
        when(specimenRepository.findByAccessionNumber("ACC-SYSMEX")).thenReturn(Optional.of(sysmexSpec));
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));

        dispatcher.dispatch(mindray, "10.0.0.1:1");
        dispatcher.dispatch(sysmex,  "10.0.0.2:1");

        // Both messages produced a saved row — the shared MSH-10 did
        // NOT collapse them. This is the regression Copilot flagged.
        verify(labResultRepository, times(2)).save(any(LabResult.class));
    }

    @Test
    @DisplayName("Sysmex 3-OBX CBC end-to-end → three rows persist, critical HGB on OBX-1 notifies per row")
    void multiObxCbcPersistsEveryAnalyte() {
        // Real parser + real service: the full V131 contract in one hop.
        String sysmex = String.join("\r",
            "MSH|^~\\&|SYSMEX^XN-1000|LAB-A|HMS|HOSP-OUAGA|"
                + "20260515112030||ORU^R01|SXN-CBC-0001|P|2.5",
            "PID|1||MRN-0103",
            "OBR|1|ACC-2026-00499|XN-891234^SYSMEX|CBC^Complete Blood Count|||20260515111500",
            "OBX|1|NM|HGB^Haemoglobin^L||6.2|g/dL|13.0-17.0|LL|||F|||20260515112030",
            "OBX|2|NM|WBC^White blood cells^L||4.8|10*3/uL|4.0-10.0|N|||F|||20260515112030",
            "OBX|3|NM|PLT^Platelets^L||245|10*3/uL|150-400|N|||F|||20260515112030",
            "") + "\r";

        when(allowlist.resolveHospital("SYSMEX^XN-1000", "LAB-A"))
            .thenReturn(Optional.of(hospital));
        when(labResultRepository
            .findFirstBySourceSendingApplicationAndSourceSendingFacilityAndSourceMessageControlId(
                "SYSMEX^XN-1000", "LAB-A", "SXN-CBC-0001"))
            .thenReturn(Optional.empty());
        when(specimenRepository.findByAccessionNumber("ACC-2026-00499"))
            .thenReturn(Optional.of(specimen));
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));

        String ack = dispatcher.dispatch(sysmex, "10.20.30.41:5013");

        assertThat(ack).contains("MSA|AA|SXN-CBC-0001");

        ArgumentCaptor<LabResult> labCap = ArgumentCaptor.forClass(LabResult.class);
        verify(labResultRepository, times(3)).save(labCap.capture());
        assertThat(labCap.getAllValues()).extracting(LabResult::getTestCode)
            .containsExactly("HGB", "WBC", "PLT");
        assertThat(labCap.getAllValues()).extracting(LabResult::getSourceObservationSetId)
            .containsExactly("1", "2", "3");
        assertThat(labCap.getAllValues()).extracting(r -> r.getAbnormalFlag().name())
            .containsExactly("CRITICAL", "NORMAL", "NORMAL");
        // Every row is offered to the critical-value notifier; the LL
        // haemoglobin is the one that actually escalates.
        verify(criticalValueNotificationService, times(3)).notifyIfCritical(any(LabResult.class));
        // One RECEIVED integration row for the whole message.
        verify(messageRecorder, times(1)).recordMessage(
            any(), any(), eq(IntegrationMessageDirection.INBOUND), eq("ORU^R01"),
            eq(sysmex), eq(IntegrationMessageStatus.RECEIVED), any());
        // One audit event per persisted row.
        verify(auditEventLogService, times(3)).logEvent(any(AuditEventRequestDTO.class));
    }

    @Test
    @DisplayName("Unparseable ORU body — recorder logs FAILED, no save, no audit")
    void unparseableOruRecordsFailedAtDispatcher() {
        String malformed = "MSH|^~\\&|MINDRAY|LAB-A|HMS|HOSP|20260515||ORU^R01|BAD-1|P|2.5\r"
                         + "PID|1||p\r"; // no OBR / OBX

        when(allowlist.resolveHospital("MINDRAY", "LAB-A")).thenReturn(Optional.of(hospital));

        String ack = dispatcher.dispatch(malformed, "10.0.0.99:1");

        assertThat(ack).contains("MSA|AE|BAD-1").contains("Unparseable ORU");
        verify(labResultRepository, never()).save(any());
        verify(auditEventLogService, never()).logEvent(any());
        verify(messageRecorder).recordMessage(
            eq("MLLP:MINDRAY/LAB-A"), any(),
            eq(IntegrationMessageDirection.INBOUND), eq("ORU^R01"),
            eq(malformed), eq(IntegrationMessageStatus.FAILED), any());
    }
}
