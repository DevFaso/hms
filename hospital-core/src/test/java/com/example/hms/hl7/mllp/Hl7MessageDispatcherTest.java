package com.example.hms.hl7.mllp;

import com.example.hms.model.Hospital;
import com.example.hms.service.integration.MllpInboundAdtService;
import com.example.hms.service.integration.MllpInboundLabService;
import com.example.hms.service.integration.MllpInboundOutcome;
import com.example.hms.service.platform.MllpAllowedSenderService;
import com.example.hms.utility.Hl7v2MessageBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Hl7MessageDispatcherTest {

    @Mock private MllpAllowedSenderService allowlist;
    @Mock private MllpInboundLabService inboundLab;
    @Mock private MllpInboundAdtService inboundAdt;

    @InjectMocks private Hl7MessageDispatcher dispatcher;

    private Hospital hospital;

    @BeforeEach
    void setUp() {
        // The dispatcher uses the real Hl7v2MessageBuilder for parsing
        // (its parsing logic is covered by Hl7v2MessageBuilder's own
        // unit tests). The allowlist + inbound services are mocked so
        // each branch of the dispatcher can be exercised in isolation.
        dispatcher = new Hl7MessageDispatcher(
            new Hl7v2MessageBuilder(),
            allowlist,
            inboundLab,
            inboundAdt
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
        when(inboundLab.processOruR01(any(), eq(hospital), anyString(), anyString()))
            .thenReturn(MllpInboundOutcome.ACCEPTED);

        String oru = "MSH|^~\\&|MINDRAY|LAB1|HMS|HOSP1|20260428073000||ORU^R01|MSG-42|P|2.5.1\r"
                   + "PID|1||abc-uuid\r"
                   + "OBR|1|ACC-1||GLU^Glucose|||20260428073000\r"
                   + "OBX|1|NM|GLU^Glucose||5.6|mmol/L|||N\r";

        String ack = dispatcher.dispatch(oru, "10.0.0.42:54321");

        assertThat(ack).contains("MSA|AA|MSG-42");
        verify(inboundLab).processOruR01(any(), eq(hospital), eq("MINDRAY"), eq("LAB1"));
        verify(inboundAdt, never()).processAdt(any(), any(), anyString(), anyString());
    }

    @Test
    void mapsLabRejectedNotFoundToAe() {
        allowSender();
        when(inboundLab.processOruR01(any(), eq(hospital), anyString(), anyString()))
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
        when(inboundLab.processOruR01(any(), eq(hospital), anyString(), anyString()))
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
        when(inboundAdt.processAdt(any(), eq(hospital), anyString(), anyString()))
            .thenReturn(MllpInboundOutcome.ACCEPTED);

        String adt = "MSH|^~\\&|REGISTRATION|HOSP1|HMS|HOSP1|20260428073000||ADT^A01|CTRL-9|P|2.5\r"
                   + "PID|1||MRN-001||DOE^JANE^Q||19850101|F|||1 Main St^^Ouagadougou^^^BF\r"
                   + "PV1|1|I|WARD-A\r";

        assertThat(dispatcher.dispatch(adt, "10.0.0.50:1024")).contains("MSA|AA|CTRL-9");
        verify(inboundAdt).processAdt(any(), eq(hospital), eq("REGISTRATION"), eq("HOSP1"));
        verify(inboundLab, never()).processOruR01(any(), any(), anyString(), anyString());
    }

    @Test
    void mapsAdtRejectedNotFoundToAe() {
        allowSender();
        when(inboundAdt.processAdt(any(), eq(hospital), anyString(), anyString()))
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
}
