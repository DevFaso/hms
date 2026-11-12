package com.example.hms.hl7.mllp;

import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.integration.IntegrationMessageEvent;
import com.example.hms.repository.integration.IntegrationMessageEventRepository;
import com.example.hms.service.integration.MllpInboundAdtService;
import com.example.hms.service.integration.MllpInboundLabService;
import com.example.hms.service.integration.MllpInboundMergeService;
import com.example.hms.service.integration.MllpInboundOutcome;
import com.example.hms.service.integration.message.IntegrationMessageRecorder;
import com.example.hms.service.platform.MllpAllowedSenderService;
import com.example.hms.utility.Hl7v2MessageBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Real TCP/framing/dispatcher/recorder; fake clinical handlers and a stateful repository stub only. */
class MllpLocalTransportIntegrationTest {
    private final MllpAllowedSenderService allowlist = mock(MllpAllowedSenderService.class);
    private final MllpInboundLabService lab = mock(MllpInboundLabService.class);
    private final MllpInboundAdtService adt = mock(MllpInboundAdtService.class);
    private final MllpInboundMergeService merge = mock(MllpInboundMergeService.class);
    private final Map<UUID, IntegrationMessageEvent> rows = new ConcurrentHashMap<>();
    private final IntegrationMessageEventRepository repository = mock(IntegrationMessageEventRepository.class);
    private MllpTcpServer server;
    private Hospital hospital;

    @BeforeEach
    void startOnlyOnAnEphemeralLoopbackPort() throws Exception {
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("cdx-simulated-hospital");
        when(allowlist.resolveHospital("CDX", "CDX-FAC")).thenReturn(Optional.of(hospital));
        when(lab.processOruR01(anyList(), eq(hospital), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(MllpInboundOutcome.ACCEPTED);
        when(adt.processAdt(any(), eq(hospital), anyString(), anyString(), anyString()))
            .thenReturn(MllpInboundOutcome.ACCEPTED);
        when(repository.save(any())).thenAnswer(invocation -> {
            IntegrationMessageEvent row = invocation.getArgument(0);
            if (row.getId() == null) row.setId(UUID.randomUUID());
            rows.put(row.getId(), row);
            return row;
        });
        when(repository.findFirstByCorrelationIdAndStatusAndReceivedAtAfterOrderByReceivedAtDesc(any(), any(), any()))
            .thenAnswer(invocation -> rows.values().stream()
                .filter(row -> java.util.Objects.equals(row.getCorrelationId(), invocation.getArgument(0)))
                .filter(row -> row.getStatus() == invocation.getArgument(1))
                .filter(row -> row.getReceivedAt().isAfter(invocation.getArgument(2, LocalDateTime.class)))
                .max(Comparator.comparing(IntegrationMessageEvent::getReceivedAt)));
        var dispatcher = new Hl7MessageDispatcher(new Hl7v2MessageBuilder(), allowlist, lab, adt, merge,
            new IntegrationMessageRecorder(repository));
        var properties = new MllpProperties();
        properties.setEnabled(true);
        properties.setBindAddress("127.0.0.1");
        properties.setPort(0);
        properties.setReadTimeoutMs(5_000);
        server = new MllpTcpServer(properties, dispatcher);
        server.start();
        assertThat(server.getBoundPort()).isPositive();
    }

    @AfterEach
    void stopListener() {
        if (server != null) server.stop();
    }

    private static String message(String type, String id) {
        return "MSH|^~\\&|CDX|CDX-FAC|HMS|CDX-HOSP|20260101000000||" + type + "|" + id + "|P|2.5.1\r"
            + "PID|1||CDX-MRN||Test^Cdx||20000101|F\r"
            + "OBR|1|CDX-ACCESSION||CDX^Synthetic|||20260101000000\r"
            + "OBX|1|NM|CDX^Synthetic||1|unit|||N\r";
    }

    private String sendByteWise(String body) throws Exception {
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), server.getBoundPort())) {
            socket.setSoTimeout(5_000);
            socket.setTcpNoDelay(true);
            var output = socket.getOutputStream();
            output.write(0x0b);
            // Byte-wise writes exercise framing over TCP; the OS still controls packet boundaries.
            for (byte value : body.getBytes(StandardCharsets.UTF_8)) output.write(value);
            output.write(0x1c);
            output.write(0x0d);
            output.flush();
            return new String(MllpFrameCodec.readFrame(socket.getInputStream(), 65_536), StandardCharsets.UTF_8);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADT^A01", "ORU^R01"})
    void inboundAdtAndOruEchoTheControlIdInAnAcceptAcknowledgement(String type) throws Exception {
        assertThat(sendByteWise(message(type, "CDX-ACCEPT"))).contains("MSA|AA|CDX-ACCEPT");
        // The dispatcher leaves accepted-message recording to the mocked clinical services.
        assertThat(rows).isEmpty();
        if (type.startsWith("ORU")) {
            verify(lab).processOruR01(anyList(), eq(hospital), eq("CDX"), eq("CDX-FAC"), eq("CDX-ACCEPT"), anyString());
            verifyNoInteractions(adt, merge);
        } else {
            verify(adt).processAdt(any(), eq(hospital), eq("CDX"), eq("CDX-FAC"), eq("CDX-ACCEPT"));
            verifyNoInteractions(lab, merge);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADT^A01", "ORU^R01"})
    void domainRefusalsAreNegativeAcknowledgementsNotFalseSuccess(String type) throws Exception {
        when(lab.processOruR01(anyList(), eq(hospital), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(MllpInboundOutcome.REJECTED_NOT_FOUND);
        when(adt.processAdt(any(), eq(hospital), anyString(), anyString(), anyString()))
            .thenReturn(MllpInboundOutcome.REJECTED_NOT_FOUND);
        assertThat(sendByteWise(message(type, "CDX-REFUSE"))).contains("MSA|AE|CDX-REFUSE").doesNotContain("MSA|AA|", "Server-side handler error");
        if (type.startsWith("ORU")) {
            verify(lab).processOruR01(anyList(), eq(hospital), eq("CDX"), eq("CDX-FAC"), eq("CDX-REFUSE"), anyString());
            verifyNoInteractions(adt, merge);
        } else {
            verify(adt).processAdt(any(), eq(hospital), eq("CDX"), eq("CDX-FAC"), eq("CDX-REFUSE"));
            verifyNoInteractions(lab, merge);
        }
    }

    @Test
    void disallowedSenderNeverReachesClinicalHandlersAndRepeatedRejectsFoldIntoOneRow() throws Exception {
        String untrusted = message("ORU^R01", "CDX-RETRY").replace("|CDX|CDX-FAC|", "|CDX-UNKNOWN|CDX-UNKNOWN-FAC|");
        assertThat(sendByteWise(untrusted)).contains("MSA|AR|CDX-RETRY");
        assertThat(sendByteWise(untrusted)).contains("MSA|AR|CDX-RETRY");
        verifyNoInteractions(lab, adt, merge);
        assertThat(rows).hasSize(1);
        assertThat(rows.values()).allSatisfy(row -> {
            assertThat(row.getAttemptCount()).isEqualTo(2);
            assertThat(row.getStatus()).isEqualTo(IntegrationMessageStatus.FAILED);
            assertThat(row.getPayload()).isEqualTo(untrusted);
            assertThat(row.getOrganizationId()).isNull();
        });
    }

    @Test
    void differentRejectedSendersWithTheSameControlIdKeepSeparateRows() throws Exception {
        String first = message("ORU^R01", "CDX-SHARED-ID").replace("|CDX|CDX-FAC|", "|UNKNOWN-A|FAC-A|");
        String second = message("ORU^R01", "CDX-SHARED-ID").replace("|CDX|CDX-FAC|", "|UNKNOWN-B|FAC-B|");
        assertThat(sendByteWise(first)).contains("MSA|AR|CDX-SHARED-ID");
        assertThat(sendByteWise(second)).contains("MSA|AR|CDX-SHARED-ID");
        verifyNoInteractions(lab, adt, merge);
        assertThat(rows).hasSize(2);
        assertThat(rows.values()).extracting(IntegrationMessageEvent::getPayload).containsExactlyInAnyOrder(first, second);
        assertThat(rows.values()).extracting(IntegrationMessageEvent::getCorrelationId).doesNotHaveDuplicates();
        assertThat(rows.values()).allSatisfy(row -> {
            assertThat(row.getAttemptCount()).isEqualTo(1);
            assertThat(row.getStatus()).isEqualTo(IntegrationMessageStatus.FAILED);
            assertThat(row.getOrganizationId()).isNull();
        });
    }

    @Test
    void rejectedSenderAfterTheFoldWindowCreatesANewRow() throws Exception {
        String untrusted = message("ORU^R01", "CDX-EXPIRED").replace("|CDX|CDX-FAC|", "|UNKNOWN|FAC|");
        assertThat(sendByteWise(untrusted)).contains("MSA|AR|CDX-EXPIRED");
        IntegrationMessageEvent expired = rows.values().iterator().next();
        expired.setReceivedAt(LocalDateTime.now().minusHours(25));
        LocalDateTime earliestCutoff = LocalDateTime.now().minusHours(24);
        assertThat(sendByteWise(untrusted)).contains("MSA|AR|CDX-EXPIRED");
        LocalDateTime latestCutoff = LocalDateTime.now().minusHours(24);
        var cutoff = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository, org.mockito.Mockito.times(2))
            .findFirstByCorrelationIdAndStatusAndReceivedAtAfterOrderByReceivedAtDesc(any(), any(), cutoff.capture());
        assertThat(cutoff.getAllValues().get(1)).isBetween(earliestCutoff, latestCutoff);
        verifyNoInteractions(lab, adt, merge);
        assertThat(rows).hasSize(2);
        assertThat(rows.values()).allSatisfy(row -> assertThat(row.getAttemptCount()).isEqualTo(1));
    }

    @Test
    void invalidHeaderIsRejectedAndTheListenerStillAcceptsTheNextConnection() throws Exception {
        String malformed = "CDX malformed header\r";
        assertThat(sendByteWise(malformed)).contains("MSA|AR|");
        verifyNoInteractions(lab, adt, merge);
        assertThat(rows).hasSize(1);
        assertThat(rows.values()).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(IntegrationMessageStatus.FAILED);
            assertThat(row.getPayload()).isEqualTo(malformed);
            assertThat(row.getCorrelationId()).isNotBlank();
            assertThat(row.getOrganizationId()).isNull();
        });
        assertThat(sendByteWise(message("ORU^R01", "CDX-AFTER-BAD"))).contains("MSA|AA|CDX-AFTER-BAD");
        assertThat(rows).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AA", "AE"})
    void outboundSenderReadsTheReceiversFramedAckAndDoesNotTreatAeAsSuccess(String code) throws Exception {
        try (var receiver = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var workers = Executors.newSingleThreadExecutor()) {
            receiver.setSoTimeout(5_000);
            var received = workers.submit(() -> {
                try (var socket = receiver.accept()) {
                    socket.setSoTimeout(5_000);
                    String body = new String(MllpFrameCodec.readFrame(socket.getInputStream(), 65_536), StandardCharsets.UTF_8);
                    var out = socket.getOutputStream();
                    out.write(0x0b);
                    out.write(("MSH|^~\\&|CDX|CDX-FAC|HMS|CDX-HOSP|20260101000000||ACK|CDX-ACK|P|2.5.1\rMSA|" + code + "|CDX-OUT\r")
                        .getBytes(StandardCharsets.UTF_8));
                    out.write(0x1c);
                    out.write(0x0d);
                    out.flush();
                    return body;
                }
            });
            var properties = new MllpOutboundProperties();
            properties.setHost(InetAddress.getLoopbackAddress().getHostAddress());
            properties.setPort(receiver.getLocalPort());
            properties.setConnectTimeoutMs(5_000);
            properties.setReadTimeoutMs(5_000);
            var sender = new MllpOutboundSender(properties);
            String original = message("ORU^R01", "CDX-OUT");
            String ack = sender.send(original);
            assertThat(received.get(5, TimeUnit.SECONDS)).isEqualTo(original);
            assertThat(ack).contains("MSA|" + code + "|CDX-OUT");
            assertThat(sender.isPositiveAck(ack)).isEqualTo("AA".equals(code));
        }
    }
}
