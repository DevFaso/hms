package com.example.hms.service.webhook;

import com.example.hms.enums.platform.WebhookEndpointStatus;
import com.example.hms.enums.platform.WebhookEventType;
import com.example.hms.model.platform.WebhookDelivery;
import com.example.hms.model.platform.WebhookEndpoint;
import com.example.hms.repository.platform.WebhookDeliveryRepository;
import com.example.hms.repository.platform.WebhookEndpointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The one-line emit point clinical write paths call (Tier 2 item 45).
 *
 * <p>Enqueues a delivery row per subscribed ACTIVE endpoint, in the
 * caller's transaction — the outbox pattern, so an event row exists iff
 * the business change committed. Best-effort at the call site: a webhook
 * bookkeeping failure must never fail an appointment write.
 *
 * <p>Payloads are THIN by construction: event, resource type, resource
 * id, hospital id, timestamp. No names, no dates of birth, no clinical
 * narrative — the receiver fetches details through the authenticated
 * API, so no PHI ever rides in a webhook body.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookPublisher {

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryRepository deliveryRepository;

    public void publish(UUID hospitalId, WebhookEventType eventType,
                        String resourceType, UUID resourceId) {
        try {
            List<WebhookEndpoint> targets = endpointRepository.findSubscribed(
                hospitalId, WebhookEndpointStatus.ACTIVE, eventType);
            if (targets.isEmpty()) {
                return;
            }
            String payload = thinPayload(eventType, resourceType, resourceId, hospitalId);
            for (WebhookEndpoint endpoint : targets) {
                deliveryRepository.save(WebhookDelivery.builder()
                    .endpoint(endpoint)
                    .eventType(eventType)
                    .payload(payload)
                    .build());
            }
            log.debug("Enqueued {} webhook deliveries for {} {}",
                targets.size(), eventType, resourceId);
        } catch (RuntimeException ex) {
            log.warn("Failed to enqueue {} webhook for {} {}: {}",
                eventType, resourceType, resourceId, ex.getMessage());
        }
    }

    static String pingPayload(UUID hospitalId) {
        return thinPayload(WebhookEventType.PING, null, null, hospitalId);
    }

    /**
     * Hand-built on purpose: five fixed fields, all UUIDs/enums/timestamps
     * we control — no user text, so no escaping surface — and the shape is
     * pinned by test. An ObjectMapper here would be a dependency for
     * nothing.
     */
    private static String thinPayload(WebhookEventType eventType, String resourceType,
                                      UUID resourceId, UUID hospitalId) {
        StringBuilder json = new StringBuilder(160);
        json.append("{\"event\":\"").append(eventType.name()).append('"');
        if (resourceType != null) {
            json.append(",\"resourceType\":\"").append(resourceType).append('"');
        }
        if (resourceId != null) {
            json.append(",\"resourceId\":\"").append(resourceId).append('"');
        }
        json.append(",\"hospitalId\":\"").append(hospitalId).append('"');
        json.append(",\"occurredAt\":\"").append(LocalDateTime.now()).append("\"}");
        return json.toString();
    }
}
