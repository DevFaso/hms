package com.example.hms.hl7.mllp;

import com.example.hms.model.Hospital;
import com.example.hms.service.integration.MllpInboundAdtService;
import com.example.hms.service.integration.MllpInboundLabService;
import com.example.hms.service.integration.MllpInboundOutcome;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end ingestion test for ORU^R01 messages from the two analyzer
 * families HMS is contracted to interoperate with (roadmap row 23).
 *
 * <p>Each test feeds a realistic vendor sample message through the full
 * {@link Hl7MessageDispatcher} pipeline (header parse → allowlist →
 * {@link Hl7v2MessageBuilder#parseOruR01} → {@link MllpInboundLabService})
 * and asserts both the dispatcher-emitted ACK and the {@code ParsedObservation}
 * the inbound service was handed.
 *
 * <p>The sample messages below were transcribed from manufacturer
 * integration guides:
 * <ul>
 *   <li><b>Mindray BS-240 Pro</b> — clinical-chemistry analyzer; uses
 *       LOINC test codes and reports glucose / cholesterol panels.</li>
 *   <li><b>Sysmex XN-1000</b> — hematology analyzer; uses local test
 *       codes (WBC / HGB / PLT) with critical-value HH flags.</li>
 * </ul>
 *
 * <p>Both vendors emit MSH-10 as a non-empty string — the idempotency
 * key the service uses to dedupe retransmissions.
 */
@ExtendWith(MockitoExtension.class)
class OruR01VendorSampleIngestionTest {

    @Mock private MllpAllowedSenderService allowlist;
    @Mock private MllpInboundLabService inboundLab;
    @Mock private MllpInboundAdtService inboundAdt;

    private Hl7MessageDispatcher dispatcher;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        Hl7v2MessageBuilder builder = new Hl7v2MessageBuilder();
        dispatcher = new Hl7MessageDispatcher(builder, allowlist, inboundLab, inboundAdt);
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
    }

    @Test
    @DisplayName("Mindray BS-240 glucose result → AA, ParsedObservation matches OBR+OBX, MSH-10 propagated")
    void mindrayGlucoseRoundTrips() {
        // Transcribed from Mindray BS-Series Lab Integration Guide §6.2.
        // Note the LOINC binding in OBX-3 (15074-8 = Glucose in blood).
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
        when(inboundLab.processOruR01(any(), eq(hospital), anyString(), anyString(),
            anyString(), anyString()))
            .thenReturn(MllpInboundOutcome.ACCEPTED);

        String ack = dispatcher.dispatch(mindray, "10.20.30.40:5012");

        assertThat(ack).contains("MSA|AA|MINDRAY-2026-00417");

        ArgumentCaptor<Hl7v2MessageBuilder.ParsedObservation> obsCap =
            ArgumentCaptor.forClass(Hl7v2MessageBuilder.ParsedObservation.class);
        ArgumentCaptor<String> ctrlIdCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> rawBodyCap = ArgumentCaptor.forClass(String.class);
        verify(inboundLab).processOruR01(obsCap.capture(), eq(hospital),
            eq("MINDRAY^BS-240^L"), eq("LAB-A^FACILITY-1^L"),
            ctrlIdCap.capture(), rawBodyCap.capture());

        Hl7v2MessageBuilder.ParsedObservation observation = obsCap.getValue();
        assertThat(observation.placerOrderNumber()).isEqualTo("ACC-2026-00417");
        assertThat(observation.fillerOrderNumber()).isEqualTo("24f3a");
        assertThat(observation.testCode()).isEqualTo("15074-8");
        assertThat(observation.resultValue()).isEqualTo("5.7");
        assertThat(observation.resultUnit()).isEqualTo("mmol/L");
        assertThat(observation.abnormalFlag()).isEqualTo("H");
        assertThat(ctrlIdCap.getValue()).isEqualTo("MINDRAY-2026-00417");
        assertThat(rawBodyCap.getValue()).startsWith("MSH|^~\\&|MINDRAY^BS-240^L|");
    }

    @Test
    @DisplayName("Sysmex XN-1000 critically-low haemoglobin → AA, abnormal flag LL captured")
    void sysmexCriticalHemoglobinRoundTrips() {
        // Transcribed from Sysmex XN-Series Host Communication Spec
        // (Insight v7). XN reports critical lows as "LL" — our service
        // maps this to AbnormalFlag.CRITICAL.
        String sysmex = String.join("\r",
            "MSH|^~\\&|SYSMEX^XN-1000|LAB-A|HMS|HOSP-OUAGA|"
                + "20260515112030||ORU^R01|SXN-0000891234|P|2.5",
            "PID|1||MRN-0103^^^HMS^MR||OUEDRAOGO^IBRAHIM||19890212|M",
            "OBR|1|ACC-2026-00499|XN-891234^SYSMEX|CBC^Complete Blood Count|||"
                + "20260515111500",
            "OBX|1|NM|HGB^Haemoglobin^L||6.2|g/dL|13.0-17.0|LL|||F|||"
                + "20260515112030",
            "OBX|2|NM|WBC^White blood cells^L||4.8|10*3/uL|4.0-10.0|N|||F|||"
                + "20260515112030",
            "OBX|3|NM|PLT^Platelets^L||245|10*3/uL|150-400|N|||F|||"
                + "20260515112030",
            "") + "\r";

        when(allowlist.resolveHospital("SYSMEX^XN-1000", "LAB-A"))
            .thenReturn(Optional.of(hospital));
        when(inboundLab.processOruR01(any(), eq(hospital), anyString(), anyString(),
            anyString(), anyString()))
            .thenReturn(MllpInboundOutcome.ACCEPTED);

        String ack = dispatcher.dispatch(sysmex, "10.20.30.41:5013");

        assertThat(ack).contains("MSA|AA|SXN-0000891234");

        ArgumentCaptor<Hl7v2MessageBuilder.ParsedObservation> obsCap =
            ArgumentCaptor.forClass(Hl7v2MessageBuilder.ParsedObservation.class);
        ArgumentCaptor<String> ctrlIdCap = ArgumentCaptor.forClass(String.class);
        verify(inboundLab).processOruR01(obsCap.capture(), eq(hospital),
            eq("SYSMEX^XN-1000"), eq("LAB-A"),
            ctrlIdCap.capture(), anyString());

        // The current parser shape extracts the first OBX; multi-OBX
        // panels (CBC has 3) will need follow-on work to persist all
        // analytes. We assert the first-OBX-wins contract here so the
        // moment we extend parseAllObx() the test breaks loudly.
        Hl7v2MessageBuilder.ParsedObservation observation = obsCap.getValue();
        assertThat(observation.placerOrderNumber()).isEqualTo("ACC-2026-00499");
        assertThat(observation.testCode()).isEqualTo("HGB");
        assertThat(observation.resultValue()).isEqualTo("6.2");
        assertThat(observation.resultUnit()).isEqualTo("g/dL");
        assertThat(observation.abnormalFlag()).isEqualTo("LL");
        assertThat(ctrlIdCap.getValue()).isEqualTo("SXN-0000891234");
    }

    @Test
    @DisplayName("Sysmex retransmit (same MSH-10) — dispatcher delegates; service idempotency layer handles dedupe")
    void sysmexRetransmitDelegates() {
        // The dispatcher does NOT know about idempotency — it just
        // delegates. The contract is that the service layer
        // (MllpInboundLabServiceImpl) sees the same control id and
        // short-circuits. We assert the dispatcher passes the same
        // payload twice; the service-layer dedupe is covered in
        // MllpInboundLabServiceImplTest.replayShortCircuits().
        String sysmex = String.join("\r",
            "MSH|^~\\&|SYSMEX^XN-1000|LAB-A|HMS|HOSP-OUAGA|"
                + "20260515112030||ORU^R01|SXN-0000891234|P|2.5",
            "PID|1||MRN-0103^^^HMS^MR||OUEDRAOGO^IBRAHIM||19890212|M",
            "OBR|1|ACC-2026-00499|XN-891234^SYSMEX|CBC|||20260515111500",
            "OBX|1|NM|HGB^Haemoglobin||6.2|g/dL|13.0-17.0|LL",
            "") + "\r";

        when(allowlist.resolveHospital("SYSMEX^XN-1000", "LAB-A"))
            .thenReturn(Optional.of(hospital));
        when(inboundLab.processOruR01(any(), eq(hospital), anyString(), anyString(),
            anyString(), anyString()))
            .thenReturn(MllpInboundOutcome.ACCEPTED);

        dispatcher.dispatch(sysmex, "10.20.30.41:5013");
        dispatcher.dispatch(sysmex, "10.20.30.41:5013");

        // Both passes deliver the same MSH-10 — service layer is
        // responsible for collapsing the duplicate.
        verify(inboundLab, org.mockito.Mockito.times(2))
            .processOruR01(any(), eq(hospital), eq("SYSMEX^XN-1000"), eq("LAB-A"),
                eq("SXN-0000891234"), anyString());
    }

    @Test
    @DisplayName("Unknown analyzer sender → AR rejected before parsing; service never called")
    void unknownSenderRejected() {
        when(allowlist.resolveHospital("ROGUE^ANALYZER", "UNKNOWN-LAB"))
            .thenReturn(Optional.empty());

        String oru = String.join("\r",
            "MSH|^~\\&|ROGUE^ANALYZER|UNKNOWN-LAB|HMS|HOSP-OUAGA|"
                + "20260515113000||ORU^R01|ROGUE-001|P|2.5",
            "PID|1||MRN-X",
            "OBR|1|ACC-X||GLU|||20260515113000",
            "OBX|1|NM|GLU||4.4|mmol/L|||N",
            "") + "\r";

        String ack = dispatcher.dispatch(oru, "203.0.113.99:4444");

        assertThat(ack).contains("MSA|AR|ROGUE-001")
                       .contains("Sender not authorised");
        org.mockito.Mockito.verifyNoInteractions(inboundLab);
    }
}
