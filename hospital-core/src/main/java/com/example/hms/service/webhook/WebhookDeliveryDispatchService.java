package com.example.hms.service.webhook;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.platform.WebhookDeliveryStatus;
import com.example.hms.enums.platform.WebhookEndpointStatus;
import com.example.hms.model.platform.WebhookDelivery;
import com.example.hms.model.platform.WebhookEndpoint;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.platform.WebhookDeliveryRepository;
import com.example.hms.service.AuditEventLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The webhook dispatch sweep (Tier 2 item 45) — the instrument-outbox
 * mechanics (V119): pending rows under the attempt ceiling, oldest first;
 * every attempt stamps attempts/lastAttemptAt; a 2xx is SENT, anything
 * else retries until the ceiling and then lands terminally in ERROR.
 * Terminal failures count toward the endpoint's consecutive-failure
 * strike count; any success resets it; at the threshold the endpoint
 * auto-disables — a dead receiver must not accumulate an unbounded queue.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookDeliveryDispatchService {

    private static final int MAX_ERROR_LENGTH = 2000;

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeliveryTransport transport;
    private final WebhookProperties properties;
    private final AuditEventLogService auditService;
    private final Clock clock;

    @Transactional
    public int dispatchPending() {
        if (!properties.isEnabled()) {
            return 0;
        }
        LocalDateTime retryBefore = LocalDateTime.now(clock)
            .minusSeconds(properties.getRetryAfterSeconds());
        List<WebhookDelivery> batch = deliveryRepository.findDispatchable(
            WebhookDeliveryStatus.PENDING, properties.getMaxAttempts(), retryBefore,
            PageRequest.of(0, properties.getBatchSize()));
        int sent = 0;
        for (WebhookDelivery delivery : batch) {
            if (dispatchOne(delivery)) {
                sent++;
            }
        }
        if (!batch.isEmpty()) {
            log.info("Webhook sweep: {} attempted, {} delivered", batch.size(), sent);
        }
        return sent;
    }

    private boolean dispatchOne(WebhookDelivery delivery) {
        WebhookEndpoint endpoint = delivery.getEndpoint();
        delivery.setAttempts(delivery.getAttempts() + 1);
        delivery.setLastAttemptAt(LocalDateTime.now(clock));

        // A pause/disable/revoke between enqueue and sweep parks the row as
        // a terminal ERROR rather than delivering to an endpoint the admin
        // switched off.
        if (endpoint.getStatus() != WebhookEndpointStatus.ACTIVE) {
            delivery.setStatus(WebhookDeliveryStatus.ERROR);
            delivery.setLastError("Endpoint is " + endpoint.getStatus() + ".");
            deliveryRepository.save(delivery);
            return false;
        }

        long timestamp = Instant.now(clock).getEpochSecond();
        String signature = WebhookSigner.sign(endpoint.getSecret(), timestamp,
            delivery.getPayload());
        WebhookDeliveryTransport.Result result = transport.post(
            endpoint.getUrl(), delivery.getPayload(), signature, timestamp,
            delivery.getEventType().name(),
            delivery.getId() != null ? delivery.getId().toString() : "");

        delivery.setResponseStatus(result.httpStatus());
        if (result.success()) {
            delivery.setStatus(WebhookDeliveryStatus.SENT);
            delivery.setSentAt(LocalDateTime.now(clock));
            delivery.setLastError(null);
            endpoint.setConsecutiveFailures(0);
            deliveryRepository.save(delivery);
            return true;
        }

        String error = result.error() != null
            ? result.error()
            : "Receiver returned HTTP " + result.httpStatus();
        delivery.setLastError(truncate(error));
        if (delivery.getAttempts() >= properties.getMaxAttempts()) {
            delivery.setStatus(WebhookDeliveryStatus.ERROR);
            strike(endpoint);
        }
        deliveryRepository.save(delivery);
        log.warn("Webhook delivery {} attempt {}/{} failed: {}",
            delivery.getId(), delivery.getAttempts(), properties.getMaxAttempts(), error);
        return false;
    }

    private void strike(WebhookEndpoint endpoint) {
        endpoint.setConsecutiveFailures(endpoint.getConsecutiveFailures() + 1);
        if (endpoint.getConsecutiveFailures() >= properties.getFailureDisableThreshold()
                && endpoint.getStatus() == WebhookEndpointStatus.ACTIVE) {
            endpoint.setStatus(WebhookEndpointStatus.DISABLED_FAILURES);
            log.warn("Webhook endpoint {} auto-disabled after {} consecutive terminal failures",
                endpoint.getId(), endpoint.getConsecutiveFailures());
            emitDisabledAudit(endpoint);
        }
    }

    /** Best-effort, actor-less — the sweep has no request context (SYSTEM flow). */
    private void emitDisabledAudit(WebhookEndpoint endpoint) {
        try {
            auditService.logEvent(AuditEventRequestDTO.builder()
                .eventType(AuditEventType.WEBHOOK_ENDPOINT_DISABLED)
                .status(AuditStatus.SUCCESS)
                .entityType("WEBHOOK_ENDPOINT")
                .resourceId(endpoint.getId() != null ? endpoint.getId().toString() : null)
                .hospitalName(endpoint.getHospital() != null
                    ? endpoint.getHospital().getName() : null)
                .eventDescription("Webhook endpoint auto-disabled after "
                    + endpoint.getConsecutiveFailures() + " consecutive delivery failures")
                .build());
        } catch (RuntimeException ex) {
            log.warn("Failed to emit auto-disable audit for webhook endpoint {}: {}",
                endpoint.getId(), ex.getMessage());
        }
    }

    private static String truncate(String error) {
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
