package com.example.hms.service.webhook;

import com.example.hms.enums.platform.WebhookDeliveryStatus;
import com.example.hms.enums.platform.WebhookEndpointStatus;
import com.example.hms.enums.platform.WebhookEventType;
import com.example.hms.model.platform.WebhookDelivery;
import com.example.hms.model.platform.WebhookEndpoint;
import com.example.hms.repository.platform.WebhookDeliveryRepository;
import com.example.hms.service.AuditEventLogService;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Real dispatcher, signer and HTTP serialization against an in-process receiver adapter.
 * No socket or DNS lookup is permitted. Persistence and transactions are stubs;
 * these tests do not establish database claim atomicity or production TLS behavior.
 */
class WebhookReceiverContractIntegrationTest {

    private static final String URL = "https://cdx-receiver.example.invalid/hooks";
    private static final String SECRET = "cdx-fake-signing-secret";
    private static final String BODY = "{\"event\":\"APPOINTMENT_BOOKED\",\"name\":\"cdx-Testeur\"}";
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-11T12:00:00Z"));
    private final List<String> signatures = new ArrayList<>();
    private WebhookDeliveryRepository repository;
    private DnsResolver dns;
    private WebhookDeliveryTransport transport;
    private WebhookDeliveryDispatchService dispatcher;
    private MockRestServiceServer receiver;
    private WebhookDelivery delivery;
    private WebhookEndpoint endpoint;
    private WebhookProperties properties;

    @BeforeEach
    void setUp() {
        properties = new WebhookProperties();
        repository = mock(WebhookDeliveryRepository.class);
        dns = mock(DnsResolver.class);
        transport = new WebhookDeliveryTransport(properties, dns);
        RestClient.Builder builder = RestClient.builder();
        receiver = MockRestServiceServer.bindTo(builder).build();
        // Test-only adapter replacement leaves the production HTTPS shape guard in place.
        ReflectionTestUtils.setField(transport, "restClient", builder.build());
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(invocation -> now.get());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        dispatcher = new WebhookDeliveryDispatchService(repository, transport, properties,
            mock(AuditEventLogService.class), clock, mock(PlatformTransactionManager.class));
        endpoint = WebhookEndpoint.builder().url(URL).secret(SECRET)
            .status(WebhookEndpointStatus.ACTIVE).build();
        endpoint.setId(UUID.randomUUID());
        delivery = WebhookDelivery.builder().endpoint(endpoint)
            .eventType(WebhookEventType.APPOINTMENT_BOOKED).payload(BODY).build();
        delivery.setId(UUID.randomUUID());
        when(repository.findById(delivery.getId())).thenReturn(Optional.of(delivery));
        // Model eligibility in memory; assert the real service passes the configured cutoff.
        when(repository.findDispatchableIds(eq(WebhookDeliveryStatus.PENDING), anyInt(), any(), any()))
            .thenAnswer(invocation -> {
                LocalDateTime cutoff = invocation.getArgument(2);
                assertThat(invocation.<Integer>getArgument(1)).isEqualTo(properties.getMaxAttempts());
                assertThat(cutoff).isEqualTo(LocalDateTime.ofInstant(now.get(), ZoneOffset.UTC)
                    .minusSeconds(properties.getRetryAfterSeconds()));
                return eligible(cutoff) ? List.of(delivery.getId()) : List.of();
            });
        when(repository.claim(eq(delivery.getId()), eq(WebhookDeliveryStatus.PENDING), anyInt(), any(), any()))
            .thenAnswer(invocation -> {
                if (!eligible(invocation.getArgument(3))) {
                    return 0;
                }
                delivery.setAttempts(delivery.getAttempts() + 1);
                delivery.setLastAttemptAt(invocation.getArgument(4));
                return 1;
            });
    }

    private boolean eligible(LocalDateTime cutoff) {
        return delivery.getStatus() == WebhookDeliveryStatus.PENDING
            && delivery.getAttempts() < properties.getMaxAttempts()
            && (delivery.getLastAttemptAt() == null || delivery.getLastAttemptAt().isBefore(cutoff));
    }

    @AfterEach
    void close() throws IOException {
        try {
            receiver.verify();
            verifyNoInteractions(dns);
        } finally {
            transport.close();
        }
    }

    private void expectAttempt(HttpStatus response) {
        long timestamp = now.get().getEpochSecond();
        receiver.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().string(BODY))
            .andExpect(header(WebhookSigner.TIMESTAMP_HEADER, Long.toString(timestamp)))
            .andExpect(header(WebhookSigner.EVENT_HEADER, "APPOINTMENT_BOOKED"))
            .andExpect(header(WebhookSigner.DELIVERY_HEADER, delivery.getId().toString()))
            .andExpect(request -> {
                String receivedBody = ((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8);
                String receivedSignature = request.getHeaders().getFirst(WebhookSigner.SIGNATURE_HEADER);
                try {
                    Mac mac = Mac.getInstance("HmacSHA256");
                    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                    String expected = "v1=" + HexFormat.of().formatHex(mac.doFinal(
                        (timestamp + "." + receivedBody).getBytes(StandardCharsets.UTF_8)));
                    assertThat(receivedSignature).isEqualTo(expected);
                    signatures.add(receivedSignature);
                } catch (java.security.GeneralSecurityException ex) {
                    throw new AssertionError(ex);
                }
            }).andRespond(withStatus(response));
    }

    @Test
    void acceptedReceiverVerifiesSignatureAndCompletesDeliveryOnce() {
        endpoint.setConsecutiveFailures(2);
        expectAttempt(HttpStatus.ACCEPTED);
        assertThat(dispatcher.dispatchPending()).isEqualTo(1);
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.SENT);
        assertThat(delivery.getAttempts()).isEqualTo(1);
        assertThat(delivery.getResponseStatus()).isEqualTo(202);
        assertThat(delivery.getSentAt()).isEqualTo(LocalDateTime.ofInstant(now.get(), ZoneOffset.UTC));
        assertThat(endpoint.getConsecutiveFailures()).isZero();
        assertThat(dispatcher.dispatchPending()).isZero();
        assertThat(signatures).hasSize(1);
    }

    @Test
    void temporaryFailureWaitsForConfiguredBackoffThenResignsSameDelivery() {
        expectAttempt(HttpStatus.SERVICE_UNAVAILABLE);
        // Expectations are registered before the first request, at the later retry timestamp.
        Instant first = now.get();
        now.set(first.plusSeconds(properties.getRetryAfterSeconds() + 1));
        expectAttempt(HttpStatus.NO_CONTENT);
        now.set(first);
        assertThat(dispatcher.dispatchPending()).isZero();
        assertThat(delivery.getLastError()).contains("503");
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        now.set(first.plusSeconds(properties.getRetryAfterSeconds() - 1));
        assertThat(dispatcher.dispatchPending()).isZero();
        assertThat(delivery.getAttempts()).isEqualTo(1);
        now.set(first.plusSeconds(properties.getRetryAfterSeconds() + 1));
        assertThat(dispatcher.dispatchPending()).isEqualTo(1);
        assertThat(delivery.getAttempts()).isEqualTo(2);
        assertThat(delivery.getLastError()).isNull();
        assertThat(signatures).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    void exhaustedDeliveryBecomesTerminalErrorAndIsNotSentAgain() {
        Instant first = now.get();
        for (int attempt = 0; attempt < properties.getMaxAttempts(); attempt++) {
            now.set(first.plusSeconds(attempt * (properties.getRetryAfterSeconds() + 1)));
            expectAttempt(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        for (int attempt = 0; attempt < properties.getMaxAttempts(); attempt++) {
            now.set(first.plusSeconds(attempt * (properties.getRetryAfterSeconds() + 1)));
            assertThat(dispatcher.dispatchPending()).isZero();
            assertThat(delivery.getStatus()).isEqualTo(attempt + 1 == properties.getMaxAttempts()
                ? WebhookDeliveryStatus.ERROR : WebhookDeliveryStatus.PENDING);
        }
        assertThat(delivery.getAttempts()).isEqualTo(properties.getMaxAttempts());
        assertThat(endpoint.getConsecutiveFailures()).isEqualTo(1);
        now.set(now.get().plusSeconds(3600));
        assertThat(dispatcher.dispatchPending()).isZero();
        assertThat(signatures).hasSize(properties.getMaxAttempts());
    }

    @Test
    void unsafeUrlIsTerminalWithoutReachingReceiver() {
        endpoint.setUrl("http://127.0.0.1/hooks");
        assertThat(dispatcher.dispatchPending()).isZero();
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.ERROR);
        assertThat(delivery.getLastError()).contains("Refused before sending");
        assertThat(delivery.getResponseStatus()).isNull();
        assertThat(signatures).isEmpty();
    }
}
