package com.example.hms.service.webhook;

import com.example.hms.enums.platform.WebhookEndpointStatus;
import com.example.hms.enums.platform.WebhookEventType;
import com.example.hms.model.Hospital;
import com.example.hms.model.platform.WebhookDelivery;
import com.example.hms.model.platform.WebhookEndpoint;
import com.example.hms.repository.platform.WebhookDeliveryRepository;
import com.example.hms.repository.platform.WebhookEndpointRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The publisher contracts (Tier 2 item 45): one delivery row per
 * subscribed ACTIVE endpoint; the payload is VALID JSON carrying exactly
 * the thin id-reference fields and nothing else — the no-PHI-in-webhooks
 * rule is structural, pinned here by schema; and a bookkeeping failure
 * never escapes into the clinical write path that called publish.
 */
@ExtendWith(MockitoExtension.class)
class WebhookPublisherTest {

    @Mock private WebhookEndpointRepository endpointRepository;
    @Mock private WebhookDeliveryRepository deliveryRepository;

    private WebhookPublisher publisher;

    private UUID hospitalId;
    private WebhookEndpoint endpoint;

    @BeforeEach
    void setUp() {
        publisher = new WebhookPublisher(endpointRepository, deliveryRepository);
        hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        endpoint = WebhookEndpoint.builder()
            .hospital(hospital)
            .url("https://receiver.example/hook")
            .secret("whsec_x")
            .status(WebhookEndpointStatus.ACTIVE)
            .subscribedEvents(EnumSet.of(WebhookEventType.APPOINTMENT_BOOKED))
            .build();
        endpoint.setId(UUID.randomUUID());
    }

    @Test
    @DisplayName("publishes one delivery per subscribed endpoint, with a valid thin payload")
    void publishesThinPayloadPerEndpoint() throws Exception {
        UUID resourceId = UUID.randomUUID();
        when(endpointRepository.findSubscribed(
                hospitalId, WebhookEndpointStatus.ACTIVE, WebhookEventType.APPOINTMENT_BOOKED))
            .thenReturn(List.of(endpoint, endpoint));

        publisher.publish(hospitalId, WebhookEventType.APPOINTMENT_BOOKED,
            "Appointment", resourceId);

        ArgumentCaptor<WebhookDelivery> saved = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepository, times(2)).save(saved.capture());

        JsonNode payload = new ObjectMapper().readTree(saved.getValue().getPayload());
        assertThat(payload.get("event").asText()).isEqualTo("APPOINTMENT_BOOKED");
        assertThat(payload.get("resourceType").asText()).isEqualTo("Appointment");
        assertThat(payload.get("resourceId").asText()).isEqualTo(resourceId.toString());
        assertThat(payload.get("hospitalId").asText()).isEqualTo(hospitalId.toString());
        assertThat(payload.get("occurredAt").asText()).isNotBlank();
        // THE no-PHI rule, structurally: ids and timestamps, nothing else.
        assertThat(payload.fieldNames()).toIterable().containsExactlyInAnyOrder(
            "event", "resourceType", "resourceId", "hospitalId", "occurredAt");
    }

    @Test
    @DisplayName("no subscribers means no rows and no payload work")
    void noSubscribersNoRows() {
        when(endpointRepository.findSubscribed(any(), any(), any())).thenReturn(List.of());

        publisher.publish(hospitalId, WebhookEventType.APPOINTMENT_BOOKED,
            "Appointment", UUID.randomUUID());

        verifyNoInteractions(deliveryRepository);
    }

    @Test
    @DisplayName("a bookkeeping failure never escapes into the caller's clinical write")
    void failureNeverEscapes() {
        when(endpointRepository.findSubscribed(any(), any(), any()))
            .thenThrow(new RuntimeException("db hiccup"));
        UUID resourceId = UUID.randomUUID();

        assertThatCode(() -> publisher.publish(hospitalId,
                WebhookEventType.APPOINTMENT_CANCELLED, "Appointment", resourceId))
            .doesNotThrowAnyException();
    }
}
