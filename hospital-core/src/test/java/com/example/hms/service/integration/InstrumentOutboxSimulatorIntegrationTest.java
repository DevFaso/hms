package com.example.hms.service.integration;

import com.example.hms.enums.InstrumentOutboxStatus;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.hl7.mllp.MllpFrameCodec;
import com.example.hms.hl7.mllp.MllpOutboundProperties;
import com.example.hms.hl7.mllp.MllpOutboundSender;
import com.example.hms.model.InstrumentOutbox;
import com.example.hms.model.LabOrder;
import com.example.hms.repository.InstrumentOutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real dispatch and MLLP sender against an ephemeral loopback instrument; persistence is simulated. */
class InstrumentOutboxSimulatorIntegrationTest {

    private static final String PAYLOAD = "MSH|^~\\&|CDX|CDX-FAC|ANALYZER|CDX|20261111120000||OML^O21|CDX-ORDER-1|P|2.5\r"
        + "PID|1||cdx-fake-patient\rORC|NW|CDX-ORDER-1\r";
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-11T12:00:00Z"));
    private ServerSocket instrument;
    private ExecutorService executor;
    private MllpOutboundProperties properties;
    private InstrumentOutbox message;
    private InstrumentOutboxDispatchService dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        instrument = new ServerSocket(0, 8, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        instrument.setSoTimeout(5000);
        executor = Executors.newSingleThreadExecutor();
        properties = new MllpOutboundProperties();
        properties.setEnabled(true);
        properties.setHost("127.0.0.1");
        properties.setPort(instrument.getLocalPort());
        properties.setConnectTimeoutMs(5000);
        properties.setReadTimeoutMs(5000);
        var repository = mock(InstrumentOutboxRepository.class);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(invocation -> now.get());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        message = new InstrumentOutbox();
        message.setId(UUID.randomUUID());
        message.setStatus(InstrumentOutboxStatus.PENDING);
        message.setMessageType("OML^O21");
        message.setPayload(PAYLOAD);
        dispatcher = new InstrumentOutboxDispatchService(repository, new MllpOutboundSender(properties), properties, clock);
        when(repository.findDispatchable(eq(InstrumentOutboxStatus.PENDING), anyInt(), any(), any()))
            .thenAnswer(invocation -> {
                LocalDateTime cutoff = invocation.getArgument(2);
                assertThat(cutoff).isEqualTo(LocalDateTime.ofInstant(now.get(), ZoneOffset.UTC)
                    .minusSeconds(properties.getRetryAfterSeconds()));
                boolean due = message.getStatus() == InstrumentOutboxStatus.PENDING
                    && message.getAttempts() < invocation.<Integer>getArgument(1)
                    && (message.getLastAttemptAt() == null || message.getLastAttemptAt().isBefore(cutoff));
                return due ? List.of(message) : List.of();
            });
    }

    @AfterEach
    void close() throws Exception {
        instrument.close();
        executor.shutdownNow();
        assertThat(executor.awaitTermination(6, TimeUnit.SECONDS)).isTrue();
    }

    private Future<String> receive(String acknowledgement) {
        return executor.submit(() -> {
            try (var socket = instrument.accept()) {
                socket.setSoTimeout(5000);
                String received = new String(MllpFrameCodec.readFrame(socket.getInputStream(), 8192),
                    StandardCharsets.UTF_8);
                if (acknowledgement != null) {
                    MllpFrameCodec.writeFrame(socket.getOutputStream(),
                        "MSH|^~\\&|ANALYZER|CDX|CDX|CDX-FAC|20261111120000||ACK|CDX-ACK|P|2.5\r"
                            + "MSA|" + acknowledgement + "|CDX-ORDER-1\r", StandardCharsets.UTF_8);
                }
                return received;
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"AA", "CA"})
    void acceptedInstrumentMarksAckAndDoesNotResend(String acknowledgement) throws Exception {
        Future<String> received = receive(acknowledgement);
        assertThat(dispatcher.dispatchPending()).isEqualTo(1);
        assertThat(received.get(6, TimeUnit.SECONDS)).isEqualTo(PAYLOAD);
        assertThat(message.getStatus()).isEqualTo(InstrumentOutboxStatus.ACK);
        assertThat(message.getAttempts()).isEqualTo(1);
        assertThat(message.getSentAt()).isEqualTo(LocalDateTime.ofInstant(now.get(), ZoneOffset.UTC));
        assertThat(message.getLastError()).isNull();
        assertThat(dispatcher.dispatchPending()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"AE", "AR"})
    void negativeInstrumentAckIsTerminal(String acknowledgement) throws Exception {
        Future<String> received = receive(acknowledgement);
        assertThat(dispatcher.dispatchPending()).isZero();
        assertThat(received.get(6, TimeUnit.SECONDS)).isEqualTo(PAYLOAD);
        assertThat(message.getStatus()).isEqualTo(InstrumentOutboxStatus.ERROR);
        assertThat(message.getLastError()).contains("Negative acknowledgement", "MSA|" + acknowledgement);
        now.set(now.get().plusSeconds(3600));
        assertThat(dispatcher.dispatchPending()).isZero();
        assertThat(message.getAttempts()).isEqualTo(1);
    }

    @Test
    void missingAckWaitsBeforeRetryAndLaterAcceptedAttemptCompletes() throws Exception {
        Future<String> first = receive(null);
        assertThat(dispatcher.dispatchPending()).isZero();
        assertThat(first.get(6, TimeUnit.SECONDS)).isEqualTo(PAYLOAD);
        assertThat(message.getStatus()).isEqualTo(InstrumentOutboxStatus.PENDING);
        assertThat(message.getLastError()).contains("without acknowledging");
        now.set(now.get().plusSeconds(properties.getRetryAfterSeconds() - 1));
        assertThat(dispatcher.dispatchPending()).isZero();
        assertThat(message.getAttempts()).isEqualTo(1);
        now.set(now.get().plusSeconds(2));
        Future<String> retry = receive("AA");
        assertThat(dispatcher.dispatchPending()).isEqualTo(1);
        assertThat(retry.get(6, TimeUnit.SECONDS)).isEqualTo(PAYLOAD);
        assertThat(message.getStatus()).isEqualTo(InstrumentOutboxStatus.ACK);
        assertThat(message.getAttempts()).isEqualTo(2);
        assertThat(message.getLastError()).isNull();
    }

    @Test
    void repeatedMissingAckExhaustsConfiguredBudget() throws Exception {
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            Future<String> received = receive(null);
            assertThat(dispatcher.dispatchPending()).isZero();
            assertThat(received.get(6, TimeUnit.SECONDS)).isEqualTo(PAYLOAD);
            assertThat(message.getAttempts()).isEqualTo(attempt);
            now.set(now.get().plusSeconds(properties.getRetryAfterSeconds() + 1));
        }
        assertThat(message.getStatus()).isEqualTo(InstrumentOutboxStatus.ERROR);
        assertThat(dispatcher.dispatchPending()).isZero();
        assertThat(message.getAttempts()).isEqualTo(properties.getMaxAttempts());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "ENTERED_IN_ERROR"})
    void withdrawnOrderIsTerminalBeforeAnAttempt(String status) {
        LabOrder order = new LabOrder();
        order.setStatus(LabOrderStatus.valueOf(status));
        message.setLabOrder(order);
        assertThat(dispatcher.dispatchPending()).isZero();
        assertThat(message.getStatus()).isEqualTo(InstrumentOutboxStatus.ERROR);
        assertThat(message.getAttempts()).isZero();
        assertThat(message.getLastAttemptAt()).isNull();
        assertThat(message.getLastError()).startsWith("Not sent:");
    }
}
