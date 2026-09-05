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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The webhook dispatch sweep (Tier 2 item 45) — claim-then-send:
 *
 * <ol>
 *   <li>a short transaction lists candidate ids (pending, under the
 *       attempt ceiling, retry window elapsed);</li>
 *   <li>each row is CLAIMED by the conditional-update idiom (the house
 *       no-ShedLock pattern) in its own short transaction — a concurrent
 *       sweep on another instance loses the update and skips the row, so
 *       nothing is ever delivered twice;</li>
 *   <li>the HTTP call runs with NO transaction open — a slow receiver
 *       must not hold a database connection;</li>
 *   <li>the outcome is recorded in a final short transaction.</li>
 * </ol>
 *
 * <p>A 2xx is SENT and resets the endpoint's strike count; anything else
 * retries until the ceiling and then lands terminally in ERROR, striking
 * the endpoint; at the threshold the endpoint auto-disables — a dead
 * receiver must not accumulate an unbounded queue.
 */
@Service
@Slf4j
public class WebhookDeliveryDispatchService {

    private static final int MAX_ERROR_LENGTH = 2000;

    /** What the load step hands the transactionless HTTP step. */
    private record ClaimedWork(String url, String secret, String payload,
                               String eventType, String deliveryId) {
    }

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeliveryTransport transport;
    private final WebhookProperties properties;
    private final AuditEventLogService auditService;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public WebhookDeliveryDispatchService(WebhookDeliveryRepository deliveryRepository,
                                          WebhookDeliveryTransport transport,
                                          WebhookProperties properties,
                                          AuditEventLogService auditService,
                                          Clock clock,
                                          PlatformTransactionManager transactionManager) {
        this.deliveryRepository = deliveryRepository;
        this.transport = transport;
        this.properties = properties;
        this.auditService = auditService;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Deliberately NOT @Transactional — see the class javadoc. */
    public int dispatchPending() {
        if (!properties.isEnabled()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime retryBefore = now.minusSeconds(properties.getRetryAfterSeconds());
        List<UUID> candidates = transactionTemplate.execute(status ->
            deliveryRepository.findDispatchableIds(WebhookDeliveryStatus.PENDING,
                properties.getMaxAttempts(), retryBefore,
                PageRequest.of(0, properties.getBatchSize())));
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        int sent = 0;
        for (UUID deliveryId : candidates) {
            if (dispatchOne(deliveryId, retryBefore, now)) {
                sent++;
            }
        }
        log.info("Webhook sweep: {} candidates, {} delivered", candidates.size(), sent);
        return sent;
    }

    private boolean dispatchOne(UUID deliveryId, LocalDateTime retryBefore, LocalDateTime now) {
        Integer claimed = transactionTemplate.execute(status ->
            deliveryRepository.claim(deliveryId, WebhookDeliveryStatus.PENDING,
                properties.getMaxAttempts(), retryBefore, now));
        if (claimed == null || claimed != 1) {
            // Another instance took it, or it was decided since listing.
            return false;
        }

        ClaimedWork work = transactionTemplate.execute(status -> loadWork(deliveryId));
        if (work == null) {
            return false;
        }

        long timestamp = Instant.now(clock).getEpochSecond();
        String signature = WebhookSigner.sign(work.secret(), timestamp, work.payload());
        // No transaction open across the wire call.
        WebhookDeliveryTransport.Result result = transport.post(
            work.url(), work.payload(), signature, timestamp, work.eventType(),
            work.deliveryId());

        Boolean success = transactionTemplate.execute(status ->
            recordOutcome(deliveryId, result));
        return Boolean.TRUE.equals(success);
    }

    /**
     * Runs inside a short transaction: pulls what the HTTP step needs
     * (decrypting the secret via the entity read) and parks the row as a
     * terminal ERROR if the endpoint was switched off since enqueue.
     */
    private ClaimedWork loadWork(UUID deliveryId) {
        WebhookDelivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
        if (delivery == null) {
            return null;
        }
        WebhookEndpoint endpoint = delivery.getEndpoint();
        if (endpoint.getStatus() != WebhookEndpointStatus.ACTIVE) {
            delivery.setStatus(WebhookDeliveryStatus.ERROR);
            delivery.setLastError("Endpoint is " + endpoint.getStatus() + ".");
            deliveryRepository.save(delivery);
            return null;
        }
        return new ClaimedWork(endpoint.getUrl(), endpoint.getSecret(), delivery.getPayload(),
            delivery.getEventType().name(), delivery.getId().toString());
    }

    /** Runs inside a short transaction: the attempt was already counted by the claim. */
    private boolean recordOutcome(UUID deliveryId, WebhookDeliveryTransport.Result result) {
        WebhookDelivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
        if (delivery == null) {
            return false;
        }
        WebhookEndpoint endpoint = delivery.getEndpoint();
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
