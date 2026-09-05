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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The dispatch contracts (Tier 2 item 45): a 2xx is SENT and resets the
 * endpoint's strike count; anything else retries until the ceiling and
 * then lands terminally in ERROR, striking the endpoint; at the strike
 * threshold the endpoint auto-disables with an actor-less audit row; the
 * signature over timestamp.body is exactly {@link WebhookSigner}'s; an
 * endpoint switched off between enqueue and sweep is never called.
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

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        properties = new WebhookProperties();
        properties.setMaxAttempts(3);
        properties.setFailureDisableThreshold(2);
        service = new WebhookDeliveryDispatchService(
            deliveryRepository, transport, properties, auditService, clock);

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
    }

    private WebhookDelivery pending() {
        WebhookDelivery d = WebhookDelivery.builder()
            .endpoint(endpoint)
            .eventType(WebhookEventType.APPOINTMENT_BOOKED)
            .payload("{\"event\":\"APPOINTMENT_BOOKED\"}")
            .build();
        d.setId(UUID.randomUUID());
        return d;
    }

    private void sweepFinds(WebhookDelivery... deliveries) {
        when(deliveryRepository.findDispatchable(
                eq(WebhookDeliveryStatus.PENDING), anyInt(), any(), any()))
            .thenReturn(List.of(deliveries));
    }

    @Test
    @DisplayName("a 2xx is SENT, stamps sentAt and resets the endpoint's strike count")
    void successIsSent() {
        endpoint.setConsecutiveFailures(1);
        WebhookDelivery delivery = pending();
        sweepFinds(delivery);
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
    @DisplayName("the delivery goes out signed - HMAC over timestamp.body, plus the header set")
    void deliveryGoesOutSigned() {
        WebhookDelivery delivery = pending();
        sweepFinds(delivery);
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
        WebhookDelivery delivery = pending();
        sweepFinds(delivery);
        when(transport.post(anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString()))
            .thenReturn(new WebhookDeliveryTransport.Result(503, null));

        service.dispatchPending();

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.getAttempts()).isEqualTo(1);
        assertThat(delivery.getLastError()).contains("503");
        assertThat(endpoint.getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("at the attempt ceiling the delivery lands in ERROR and strikes the endpoint")
    void failureAtCeilingIsTerminal() {
        WebhookDelivery delivery = pending();
        delivery.setAttempts(2); // next attempt is the third and last
        sweepFinds(delivery);
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
        WebhookDelivery delivery = pending();
        delivery.setAttempts(2);
        sweepFinds(delivery);
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
        WebhookDelivery delivery = pending();
        sweepFinds(delivery);

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
