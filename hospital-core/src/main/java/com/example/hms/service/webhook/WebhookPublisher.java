package com.example.hms.service.webhook;

import com.example.hms.enums.platform.WebhookEndpointStatus;
import com.example.hms.enums.platform.WebhookEventType;
import com.example.hms.model.platform.WebhookDelivery;
import com.example.hms.model.platform.WebhookEndpoint;
import com.example.hms.repository.platform.WebhookDeliveryRepository;
import com.example.hms.repository.platform.WebhookEndpointRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The one-line emit point clinical write paths call (Tier 2 item 45).
 *
 * <p><b>The contract, chosen explicitly:</b> webhook bookkeeping must
 * NEVER fail or roll back a clinical write. So nothing here joins the
 * caller's transaction — inside an active transaction the enqueue is
 * deferred to AFTER COMMIT (no event is ever emitted for a write that
 * rolled back) and then runs in its own REQUIRES_NEW transaction, where a
 * failure is logged and swallowed. The price, stated honestly: a crash in
 * the instant between the caller's commit and the enqueue loses that
 * event. For third-party notifications that is the right side of the
 * trade — the alternative (enqueue in the caller's transaction) lets a
 * webhook-table failure mark the appointment write rollback-only.
 *
 * <p>Payloads are THIN by construction: event, resource type, resource
 * id, hospital id, timestamp. No names, no dates of birth, no clinical
 * narrative — the receiver fetches details through the authenticated
 * API, so no PHI ever rides in a webhook body.
 */
@Component
@Slf4j
public class WebhookPublisher {

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final TransactionTemplate enqueueTransaction;

    public WebhookPublisher(WebhookEndpointRepository endpointRepository,
                            WebhookDeliveryRepository deliveryRepository,
                            PlatformTransactionManager transactionManager) {
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
        this.enqueueTransaction = new TransactionTemplate(transactionManager);
        this.enqueueTransaction.setPropagationBehavior(
            TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void publish(UUID hospitalId, WebhookEventType eventType,
                        String resourceType, UUID resourceId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        enqueueSafely(hospitalId, eventType, resourceType, resourceId);
                    }
                });
        } else {
            enqueueSafely(hospitalId, eventType, resourceType, resourceId);
        }
    }

    private void enqueueSafely(UUID hospitalId, WebhookEventType eventType,
                               String resourceType, UUID resourceId) {
        try {
            enqueueTransaction.executeWithoutResult(status -> {
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
            });
        } catch (RuntimeException ex) {
            log.warn("Failed to enqueue {} webhook for {} {}: {}",
                eventType, resourceType, resourceId, ex.getMessage());
        }
    }

    static String pingPayload(UUID hospitalId) {
        return thinPayload(WebhookEventType.PING, null, null, hospitalId);
    }

    /**
     * Hand-built on purpose: five fixed fields, all UUIDs/enums/instants
     * we control — no user text, so no escaping surface — and the shape is
     * pinned by test. occurredAt is an RFC 3339 UTC instant: a wire
     * timestamp must be absolute, never a zone-free local time.
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
        json.append(",\"occurredAt\":\"").append(Instant.now()).append("\"}");
        return json.toString();
    }
}
