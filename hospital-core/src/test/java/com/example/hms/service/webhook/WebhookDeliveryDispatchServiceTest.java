package com.example.hms.service.webhook;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.platform.WebhookDeliveryStatus;
import com.example.hms.enums.platform.WebhookEndpointStatus;
import com.example.hms.enums.platform.WebhookEventType;
import com.example.hms.model.Hospital;
import com.example.hms.model.platform.WebhookDelivery;
import com.example.hms.model.platform.WebhookEndpoint;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.platform.WebhookDeliveryRepository;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The dispatch contracts (Tier 2 item 45), claim-then-send: a row is
 * CLAIMED by conditional update before any network work, so a lost claim
 * (another instance won) means no HTTP call at all; the HTTP call runs
 * outside any transaction; a 2xx is SENT and resets the endpoint's strike
 * count; anything else retries until the ceiling and then lands
 * terminally in ERROR, striking the endpoint; at the strike threshold the
 * endpoint auto-disables with an actor-less audit row; the signature over
 * timestamp.body is exactly {@link WebhookSigner}'s.
 */
@ExtendWith(MockitoExtension.class)
class WebhookDeliveryDispatchServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 4, 9, 0);

    @Mock private WebhookDeliveryRepository deliveryRepository;
    @Mock private WebhookDeliveryTransport transport;
    @Mock private AuditEventLogService auditService;

    private WebhookProperties properties;
    private WebhookDeliveryDispatchService service;
    private Clock clock;

    private WebhookEndpoint endpoint;
    private WebhookDelivery delivery;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        properties = new WebhookProperties();
        properties.setMaxAttempts(3);
        properties.setFailureDisableThreshold(2);
        // A bare mock manager: TransactionTemplate runs the callback
        // inline, which is exactly what a unit test wants.
        service = new WebhookDeliveryDispatchService(deliveryRepository, transport, properties,
            auditService, clock, mock(PlatformTransactionManager.class));

        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("General");
        endpoint = WebhookEndpoint.builder()
            .hospital(hospital)
            .url("https://receiver.example/hook")
            .secret("whsec_test")
            .status(WebhookEndpointStatus.ACTIVE)
            .subscribedEvents(EnumSet.of(WebhookEventType.APPOINTMENT_BOOKED))
            .build();
        endpoint.setId(UUID.randomUUID());

        delivery = WebhookDelivery.builder()
            .endpoint(endpoint)
            .eventType(WebhookEventType.APPOINTMENT_BOOKED)
            .payload("{\"event\":\"APPOINTMENT_BOOKED\"}")
            .build();
        delivery.setId(UUID.randomUUID());
    }

    private void sweepFindsTheDelivery() {
        when(deliveryRepository.findDispatchableIds(
                eq(WebhookDeliveryStatus.PENDING), anyInt(), any(), any()))
            .thenReturn(List.of(delivery.getId()));
    }

    private void claimSucceeds() {
        when(deliveryRepository.claim(eq(delivery.getId()), eq(WebhookDeliveryStatus.PENDING),
                anyInt(), any(), any()))
            .thenReturn(1);
        when(deliveryRepository.findById(delivery.getId())).thenReturn(Optional.of(delivery));
        // The claim already counted this attempt.
        delivery.setAttempts(delivery.getAttempts() + 1);
    }

    @Test
    @DisplayName("a 2xx is SENT, stamps sentAt and resets the endpoint's strike count")
    void successIsSent() {
        endpoint.setConsecutiveFailures(1);
        sweepFindsTheDelivery();
        claimSucceeds();
        when(transport.post(anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString()))
            .thenReturn(new WebhookDeliveryTransport.Result(200, null));

        int sent = service.dispatchPending();

        assertThat(sent).isEqualTo(1);
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.SENT);
        assertThat(delivery.getSentAt()).isEqualTo(NOW);
        assertThat(delivery.getResponseStatus()).isEqualTo(200);
        assertThat(endpoint.getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("a lost claim means another instance won the row - no HTTP call, no double send")
    void lostClaimSkipsTheRow() {
        sweepFindsTheDelivery();
        when(deliveryRepository.claim(eq(delivery.getId()), eq(WebhookDeliveryStatus.PENDING),
                anyInt(), any(), any()))
            .thenReturn(0);

        int sent = service.dispatchPending();

        assertThat(sent).isZero();
        verifyNoInteractions(transport);
    }

    @Test
    @DisplayName("the delivery goes out signed - HMAC over timestamp.body, plus the header set")
    void deliveryGoesOutSigned() {
        sweepFindsTheDelivery();
        claimSucceeds();
        when(transport.post(anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString()))
            .thenReturn(new WebhookDeliveryTransport.Result(200, null));

        service.dispatchPending();

        long expectedTimestamp = Instant.now(clock).getEpochSecond();
        String expectedSignature = WebhookSigner.sign(
            "whsec_test", expectedTimestamp, delivery.getPayload());
        verify(transport).post(
            eq("https://receiver.example/hook"),
            eq(delivery.getPayload()),
            eq(expectedSignature),
            eq(expectedTimestamp),
            eq("APPOINTMENT_BOOKED"),
            eq(delivery.getId().toString()));
    }

    @Test
    @DisplayName("a non-2xx below the ceiling stays PENDING for the next sweep")
    void failureBelowCeilingRetries() {
        sweepFindsTheDelivery();
        claimSucceeds();
        when(transport.post(anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString()))
            .thenReturn(new WebhookDeliveryTransport.Result(503, null));

        service.dispatchPending();

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.getLastError()).contains("503");
        assertThat(endpoint.getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("at the attempt ceiling the delivery lands in ERROR and strikes the endpoint")
    void failureAtCeilingIsTerminal() {
        delivery.setAttempts(2); // the claim makes this the third and last attempt
        sweepFindsTheDelivery();
        claimSucceeds();
        when(transport.post(anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString()))
            .thenReturn(new WebhookDeliveryTransport.Result(null, "ConnectException: refused"));

        service.dispatchPending();

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.ERROR);
        assertThat(delivery.getLastError()).contains("ConnectException");
        assertThat(endpoint.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("at the strike threshold the endpoint auto-disables with an actor-less audit row")
    void strikeThresholdAutoDisables() {
        endpoint.setConsecutiveFailures(1); // one more terminal failure hits the threshold of 2
        delivery.setAttempts(2);
        sweepFindsTheDelivery();
        claimSucceeds();
        when(transport.post(anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString()))
            .thenReturn(new WebhookDeliveryTransport.Result(500, null));

        service.dispatchPending();

        assertThat(endpoint.getStatus()).isEqualTo(WebhookEndpointStatus.DISABLED_FAILURES);
        ArgumentCaptor<AuditEventRequestDTO> audit =
            ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventType())
            .isEqualTo(AuditEventType.WEBHOOK_ENDPOINT_DISABLED);
        assertThat(audit.getValue().getUserId()).isNull();
    }

    @Test
    @DisplayName("an endpoint switched off between enqueue and sweep is never called")
    void switchedOffEndpointIsNeverCalled() {
        endpoint.setStatus(WebhookEndpointStatus.PAUSED);
        sweepFindsTheDelivery();
        claimSucceeds();

        service.dispatchPending();

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.ERROR);
        assertThat(delivery.getLastError()).contains("PAUSED");
        verifyNoInteractions(transport);
    }

    @Test
    @DisplayName("the master switch stops the sweep cold")
    void disabledSweepDoesNothing() {
        properties.setEnabled(false);

        assertThat(service.dispatchPending()).isZero();

        verifyNoInteractions(deliveryRepository, transport);
    }
}
