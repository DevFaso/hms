package com.example.hms.hl7.mllp;

import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.model.Hospital;
import com.example.hms.service.integration.MllpInboundAdtService;
import com.example.hms.service.integration.MllpInboundLabService;
import com.example.hms.service.integration.MllpInboundMergeService;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedMergeMessage;
import com.example.hms.service.integration.MllpInboundOutcome;
import com.example.hms.service.integration.message.IntegrationMessageRecorder;
import com.example.hms.service.platform.MllpAllowedSenderService;
import com.example.hms.utility.Hl7v2MessageBuilder;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Hl7MessageDispatcherTest {

    @Mock private MllpAllowedSenderService allowlist;
    @Mock private MllpInboundLabService inboundLab;
    @Mock private MllpInboundAdtService inboundAdt;
    @Mock private MllpInboundMergeService inboundMerge;
    @Mock private IntegrationMessageRecorder messageRecorder;

    // The dispatcher is constructed manually rather than via @InjectMocks
    // because we want to inject the real Hl7v2MessageBuilder (its parsing
    // logic is covered by its own unit tests) alongside Mockito mocks for
    // the other collaborators.
    private Hl7MessageDispatcher dispatcher;

    private Hospital hospital;

    @BeforeEach
    void setUp() {
        dispatcher = new Hl7MessageDispatcher(
            new Hl7v2MessageBuilder(),
            allowlist,
            inboundLab,
            inboundAdt,
            inboundMerge,
            messageRecorder
        );

        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("Allowlisted Hospital");
    }

    private void allowSender() {
        when(allowlist.resolveHospital(anyString(), anyString()))
            .thenReturn(Optional.of(hospital));
    }

    @Test
    void rejectsUnknownSenderWithAr_beforeAnyDomainWork() {
        when(allowlist.resolveHospital(anyString(), anyString())).thenReturn(Optional.empty());

        String oru = "MSH|^~\\&|ROGUE|UNKNOWN|HMS|HOSP1|20260428073000||ORU^R01|MSG-42|P|2.5.1\r"
                   + "PID|1||abc-uuid\r"
                   + "OBR|1|ACC-1||GLU^Glucose|||20260428073000\r"
                   + "OBX|1|NM|GLU^Glucose||5.6|mmol/L|||N\r";

        String ack = dispatcher.dispatch(oru, "10.0.0.42:54321");

        assertThat(ack)
            .contains("MSA|AR|MSG-42")
            .contains("Sender not authorised");
        verifyNoInteractions(inboundLab, inboundAdt);
    }

    @Test
    void acceptedOruR01EmitsAa() {
        allowSender();
        when(inboundLab.processOruR01(any(), eq(hospital), anyString(), anyString(), any(), anyString()))
            .thenReturn(MllpInboundOutcome.ACCEPTED);

        String oru = "MSH|^~\\&|MINDRAY|LAB1|HMS|HOSP1|20260428073000||ORU^R01|MSG-42|P|2.5.1\r"
                   + "PID|1||abc-uuid\r"
                   + "OBR|1|ACC-1||GLU^Glucose|||20260428073000\r"
                   + "OBX|1|NM|GLU^Glucose||5.6|mmol/L|||N\r";

        String ack = dispatcher.dispatch(oru, "10.0.0.42:54321");

        assertThat(ack).contains("MSA|AA|MSG-42");
        verify(inboundLab).processOruR01(any(), eq(hospital), eq("MINDRAY"), eq("LAB1"),
            eq("MSG-42"), anyString());
        verify(inboundAdt, never()).processAdt(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void mapsLabRejectedNotFoundToAe() {
        allowSender();
        when(inboundLab.processOruR01(any(), eq(hospital), anyString(), anyString(), any(), anyString()))
            .thenReturn(MllpInboundOutcome.REJECTED_NOT_FOUND);

        String oru = "MSH|^~\\&|MINDRAY|LAB1|HMS|HOSP1|20260428073000||ORU^R01|MSG-7|P|2.5.1\r"
                   + "PID|1||p\r"
                   + "OBR|1|ACC-MISSING||GLU^Glucose|||20260428\r"
                   + "OBX|1|NM|GLU^Glucose||5.6|mmol/L|||N\r";

        assertThat(dispatcher.dispatch(oru, "10.0.0.1:1"))
            .contains("MSA|AE|MSG-7")
            .contains("ORU^R01 referenced entity not found");
    }

    @Test
    void mapsLabRejectedCrossTenantToAr() {
        allowSender();
        when(inboundLab.processOruR01(any(), eq(hospital), anyString(), anyString(), any(), anyString()))
            .thenReturn(MllpInboundOutcome.REJECTED_CROSS_TENANT);

        String oru = "MSH|^~\\&|MINDRAY|LAB1|HMS|HOSP1|20260428||ORU^R01|MSG-8|P|2.5\r"
                   + "PID|1||p\r"
                   + "OBR|1|ACC-OTHER||GLU^Glucose|||20260428\r"
                   + "OBX|1|NM|GLU^Glucose||5.6|mmol/L|||N\r";

        assertThat(dispatcher.dispatch(oru, "10.0.0.1:1"))
            .contains("MSA|AR|MSG-8")
            .contains("sender not authorised");
    }

    @Test
    void emitsAeWhenOruParsingFails() {
        allowSender();
        String malformedOru = "MSH|^~\\&|S|F|HMS|HOSP|20260428||ORU^R01|MSG-9|P|2.5\r"
                            + "PID|1||p\r";
        String ack = dispatcher.dispatch(malformedOru, "10.0.0.10:1");

        assertThat(ack).contains("MSA|AE|MSG-9").contains("Unparseable ORU^R01");
        verifyNoInteractions(inboundLab);
    }

    @Test
    void acceptedAdtA01EmitsAa() {
        allowSender();
        // Dispatcher now passes MSH-10 control id through the 5-arg
        // processAdt overload (roadmap row 24 — visit-sync projection
        // stamps the control id on the reconciled Admission/Encounter).
        when(inboundAdt.processAdt(any(), eq(hospital), anyString(), anyString(), any()))
            .thenReturn(MllpInboundOutcome.ACCEPTED);

        String adt = "MSH|^~\\&|REGISTRATION|HOSP1|HMS|HOSP1|20260428073000||ADT^A01|CTRL-9|P|2.5\r"
                   + "PID|1||MRN-001||DOE^JANE^Q||19850101|F|||1 Main St^^Ouagadougou^^^BF\r"
                   + "PV1|1|I|WARD-A\r";

        assertThat(dispatcher.dispatch(adt, "10.0.0.50:1024")).contains("MSA|AA|CTRL-9");
        verify(inboundAdt).processAdt(any(), eq(hospital),
            eq("REGISTRATION"), eq("HOSP1"), eq("CTRL-9"));
        verify(inboundLab, never()).processOruR01(any(), any(), anyString(), anyString(),
            any(), anyString());
    }

    @Test
    void mapsAdtRejectedNotFoundToAe() {
        allowSender();
        when(inboundAdt.processAdt(any(), eq(hospital), anyString(), anyString(), any()))
            .thenReturn(MllpInboundOutcome.REJECTED_NOT_FOUND);

        String adt = "MSH|^~\\&|REGISTRATION|HOSP1|HMS|HOSP1|20260428||ADT^A08|CTRL-N|P|2.5\r"
                   + "PID|1||MRN-UNKNOWN||DOE^JANE\r";

        assertThat(dispatcher.dispatch(adt, "10.0.0.51:1"))
            .contains("MSA|AE|CTRL-N")
            .contains("referenced entity not found");
    }

    @Test
    void emitsAeWhenAdtParsingFails_missingPid() {
        allowSender();
        String adtNoPid = "MSH|^~\\&|REGISTRATION|HOSP1|HMS|HOSP1|20260428||ADT^A01|CTRL-X|P|2.5\r";

        assertThat(dispatcher.dispatch(adtNoPid, "10.0.0.51:1"))
            .contains("MSA|AE|CTRL-X")
            .contains("Unparseable ADT^A01");
        verifyNoInteractions(inboundAdt);
    }

    @Test
    void rejectsUnsupportedMessageTypeWithAr() {
        allowSender();
        String unknown = "MSH|^~\\&|X|Y|HMS|HOSP1|20260428073000||ZZZ^Z99|C-1|P|2.5\r";
        assertThat(dispatcher.dispatch(unknown, "10.0.0.51:1"))
            .contains("MSA|AR|C-1")
            .contains("Unsupported message type ZZZ^Z99");
    }

    @Test
    void rejectsMessageWithBadHeader_beforeAllowlistCheck() {
        String bad = "GARBAGE|||";
        assertThat(dispatcher.dispatch(bad, "10.0.0.99:1"))
            .contains("MSA|AR|")
            .contains("Invalid MSH");
        verifyNoInteractions(allowlist, inboundLab, inboundAdt);
    }

    // ── Pre-service reject paths must reach the recorder ─────────────
    // Before this PR the IntegrationMessageRecorder was only called
    // from inside MllpInboundLabService, so dispatcher-level rejects
    // (invalid MSH, allowlist miss, unparseable ORU, unsupported type,
    // unparseable ADT) never appeared in the DLQ/replay surface.
    // These four assertions lock that contract in place.

    @Test
    void recorderInvokedOnInvalidMshReject() {
        String bad = "GARBAGE|||";
        dispatcher.dispatch(bad, "10.0.0.99:1");
        verify(messageRecorder).recordMessage(
            eq("MLLP:?/?"), isNull(),
            eq(IntegrationMessageDirection.INBOUND),
            eq("UNKNOWN"),
            eq(bad),
            eq(IntegrationMessageStatus.FAILED),
            contains("Invalid MSH"));
    }

    @Test
    void recorderInvokedOnAllowlistReject() {
        when(allowlist.resolveHospital(anyString(), anyString())).thenReturn(Optional.empty());
        String oru = "MSH|^~\\&|ROGUE|UNKNOWN|HMS|HOSP1|20260428073000||ORU^R01|MSG-42|P|2.5.1\r"
                   + "PID|1||abc\rOBR|1|ACC-1||GLU|||20260428073000\r"
                   + "OBX|1|NM|GLU||5.6|mmol/L|||N\r";
        dispatcher.dispatch(oru, "10.0.0.1:1");
        verify(messageRecorder).recordMessage(
            eq("MLLP:ROGUE/UNKNOWN"), isNull(),
            eq(IntegrationMessageDirection.INBOUND),
            eq("ORU^R01"),
            eq(oru),
            eq(IntegrationMessageStatus.FAILED),
            contains("not allowlisted"));
    }

    @Test
    void recorderInvokedOnUnparseableOru() {
        allowSender();
        String malformedOru = "MSH|^~\\&|S|F|HMS|HOSP|20260428||ORU^R01|MSG-9|P|2.5\r"
                            + "PID|1||p\r";
        dispatcher.dispatch(malformedOru, "10.0.0.10:1");
        verify(messageRecorder).recordMessage(
            eq("MLLP:S/F"), any(),
            eq(IntegrationMessageDirection.INBOUND),
            eq("ORU^R01"),
            eq(malformedOru),
            eq(IntegrationMessageStatus.FAILED),
            contains("unparseable"));
    }

    @Test
    void recorderInvokedOnUnsupportedMessageType() {
        allowSender();
        String unknown = "MSH|^~\\&|X|Y|HMS|HOSP1|20260428073000||ZZZ^Z99|C-1|P|2.5\r";
        dispatcher.dispatch(unknown, "10.0.0.51:1");
        verify(messageRecorder).recordMessage(
            eq("MLLP:X/Y"), any(),
            eq(IntegrationMessageDirection.INBOUND),
            eq("ZZZ^Z99"),
            eq(unknown),
            eq(IntegrationMessageStatus.FAILED),
            contains("unsupported message type"));
    }

    @Test
    void recorderInvokedOnUnparseableAdt() {
        allowSender();
        String adtNoPid = "MSH|^~\\&|REGISTRATION|HOSP1|HMS|HOSP1|20260428||ADT^A01|CTRL-X|P|2.5\r";
        dispatcher.dispatch(adtNoPid, "10.0.0.51:1");
        verify(messageRecorder).recordMessage(
            eq("MLLP:REGISTRATION/HOSP1"), any(),
            eq(IntegrationMessageDirection.INBOUND),
            eq("ADT^A01"),
            eq(adtNoPid),
            eq(IntegrationMessageStatus.FAILED),
            contains("unparseable"));
    }

    @Test
    void recorderNotInvokedOnAcceptedOru_serviceOwnsThatRecord() {
        allowSender();
        when(inboundLab.processOruR01(any(), eq(hospital), anyString(), anyString(),
                any(), anyString()))
            .thenReturn(MllpInboundOutcome.ACCEPTED);

        String oru = "MSH|^~\\&|MINDRAY|LAB1|HMS|HOSP1|20260428073000||ORU^R01|MSG-OK|P|2.5.1\r"
                   + "PID|1||p\r"
                   + "OBR|1|ACC-1||GLU||20260428073000\r"
                   + "OBX|1|NM|GLU||5.6|mmol/L|||N\r";
        dispatcher.dispatch(oru, "10.0.0.1:1");

        // The accepted path's record is the service's responsibility —
        // recording at both layers would double-count successful
        // ingestions in the DLQ surface.
        verifyNoInteractions(messageRecorder);
    }

    /* ── ADT^A40 patient merge (Tier 2 item 41) ──────────────────────── */

    private static final String A40 =
        "MSH|^~\\&|REGISTRATION|HOSP1|HMS|HOSP1|20260826120000||ADT^A40|CTRL-A40|P|2.5\r"
        + "EVN|A40|20260826120000\r"
        + "PID|1||MRN-SURVIVOR^^^HOSP1^MR||Traore^Awa||19900101|F\r"
        + "MRG|MRN-RETIRED^^^HOSP1^MR\r";

    @Test
    void routesA40ToTheMergeHandlerAndNotTheDemographicOne() {
        // The failure this guards is silent: handleAdt would have parsed the
        // PID, never looked for MRG, and applied a demographic update instead
        // of a merge — the wrong thing done quietly rather than a reject.
        allowSender();
        when(inboundMerge.processMerge(any(), eq(hospital), anyString(), anyString(), any()))
            .thenReturn(MllpInboundOutcome.ACCEPTED);

        assertThat(dispatcher.dispatch(A40, "10.0.0.60:1024")).contains("MSA|AA|CTRL-A40");

        verify(inboundMerge).processMerge(any(), eq(hospital),
            eq("REGISTRATION"), eq("HOSP1"), eq("CTRL-A40"));
        verify(inboundAdt, never()).processAdt(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void passesTheSurvivorAndRetireeToTheMergeServiceTheRightWayRound() {
        allowSender();
        when(inboundMerge.processMerge(any(), eq(hospital), anyString(), anyString(), any()))
            .thenReturn(MllpInboundOutcome.ACCEPTED);

        dispatcher.dispatch(A40, "10.0.0.60:1024");

        ArgumentCaptor<ParsedMergeMessage> parsed = ArgumentCaptor.forClass(ParsedMergeMessage.class);
        verify(inboundMerge).processMerge(parsed.capture(), any(), anyString(), anyString(), any());
        // Backwards here merges away the patient that was meant to survive.
        assertThat(parsed.getValue().survivingMrn()).isEqualTo("MRN-SURVIVOR");
        assertThat(parsed.getValue().priorMrn()).isEqualTo("MRN-RETIRED");
    }

    @Test
    void anA40WithoutAnMrgSegmentIsAeAndNeverReachesTheMergeService() {
        allowSender();

        String noMrg = "MSH|^~\\&|REGISTRATION|HOSP1|HMS|HOSP1|20260826||ADT^A40|CTRL-BAD|P|2.5\r"
                     + "PID|1||MRN-SURVIVOR||Traore^Awa\r";

        assertThat(dispatcher.dispatch(noMrg, "10.0.0.61:1")).contains("MSA|AE|CTRL-BAD");
        verifyNoInteractions(inboundMerge);
        verify(inboundAdt, never()).processAdt(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void aCrossTenantMergeRejectionBecomesAr() {
        allowSender();
        when(inboundMerge.processMerge(any(), eq(hospital), anyString(), anyString(), any()))
            .thenReturn(MllpInboundOutcome.REJECTED_CROSS_TENANT);

        assertThat(dispatcher.dispatch(A40, "10.0.0.62:1")).contains("MSA|AR|CTRL-A40");
    }

    @Test
    void anUnknownIdentifierMergeRejectionBecomesAe() {
        allowSender();
        when(inboundMerge.processMerge(any(), eq(hospital), anyString(), anyString(), any()))
            .thenReturn(MllpInboundOutcome.REJECTED_NOT_FOUND);

        assertThat(dispatcher.dispatch(A40, "10.0.0.63:1")).contains("MSA|AE|CTRL-A40");
    }

    @Test
    void anA40FromAnUnknownSenderNeverReachesTheMergeService() {
        // The allowlist gate runs before any parsing or domain work.
        when(allowlist.resolveHospital(anyString(), anyString())).thenReturn(Optional.empty());

        assertThat(dispatcher.dispatch(A40, "10.0.0.64:1")).contains("MSA|AR|CTRL-A40");
        verifyNoInteractions(inboundMerge);
    }
}
