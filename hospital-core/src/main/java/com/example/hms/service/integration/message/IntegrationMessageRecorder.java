package com.example.hms.service.integration.message;

import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.model.integration.IntegrationMessageEvent;
import com.example.hms.repository.integration.IntegrationMessageEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * MVP-c3 — Bridges-style per-message recorder. Sibling of
 * {@link com.example.hms.service.integration.health.IntegrationHealthRecorder}:
 * the health recorder captures *probe* outcomes (one row per "is this
 * integration healthy right now?"); this one captures *messages*
 * (one row per actual partner-protocol payload that crossed the wire).
 *
 * <p>All public methods run in {@link Propagation#REQUIRES_NEW} so a
 * caller's transaction can roll back without losing the trace, and they
 * never throw — a bug in the recorder must not fail the partner-side
 * write that triggered it. PHI in the payload is truncated at
 * {@link #MAX_PAYLOAD_CHARS}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IntegrationMessageRecorder {

    /** Hard cap so a 10 MB FHIR Bundle can't blow up the audit table. */
    static final int MAX_PAYLOAD_CHARS = 64 * 1024;
    private static final int MAX_ERROR_CHARS = 2_000;

    private final IntegrationMessageEventRepository repository;

    /**
     * Record an outbound or inbound message as it crossed the wire.
     * Returns the persisted entity so the caller (e.g. a synchronous
     * partner adapter) can capture the {@code correlationId} and pass
     * it through downstream logs. Returns {@code null} on persistence
     * failure — the caller does not need to special-case it.
     *
     * <p>Sonar review fix — renamed from {@code record} to
     * {@code recordMessage} so it does not collide with Java 21's
     * contextual {@code record} keyword.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IntegrationMessageEvent recordMessage(
        String integrationId,
        UUID organizationId,
        IntegrationMessageDirection direction,
        String messageType,
        String payload,
        IntegrationMessageStatus status,
        String errorMessage
    ) {
        try {
            String correlationId = UUID.randomUUID().toString();
            IntegrationMessageEvent event = IntegrationMessageEvent.builder()
                .integrationId(integrationId)
                .organizationId(organizationId)
                .direction(direction)
                .messageType(messageType)
                .correlationId(correlationId)
                .payload(truncate(payload, MAX_PAYLOAD_CHARS))
                .status(status)
                .errorMessage(truncate(errorMessage, MAX_ERROR_CHARS))
                .attemptCount(1)
                .lastAttemptedAt(LocalDateTime.now())
                .receivedAt(LocalDateTime.now())
                .build();
            return repository.save(event);
        } catch (RuntimeException ex) {
            // Best-effort — same posture as IntegrationHealthRecorder.
            log.error("[INTEGRATION-MESSAGE] Failed to record message for {}", integrationId, ex);
            return null;
        }
    }

    /**
     * Mark a previously-FAILED message as replayed. The same
     * {@code correlationId} is preserved so an operator can read the
     * full retry history; {@code attemptCount} is incremented so a
     * UI counter reflects how many times we've tried.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IntegrationMessageEvent recordReplay(
        UUID originalMessageId,
        IntegrationMessageStatus newStatus,
        String errorMessage
    ) {
        try {
            return repository.findById(originalMessageId)
                .map(original -> persistReplay(original, newStatus, errorMessage))
                .orElse(null);
        } catch (RuntimeException ex) {
            log.error("[INTEGRATION-MESSAGE] Failed to record replay for {}", originalMessageId, ex);
            return null;
        }
    }

    private IntegrationMessageEvent persistReplay(
        IntegrationMessageEvent original,
        IntegrationMessageStatus newStatus,
        String errorMessage
    ) {
        IntegrationMessageEvent replay = IntegrationMessageEvent.builder()
            .integrationId(original.getIntegrationId())
            .organizationId(original.getOrganizationId())
            .direction(original.getDirection())
            .messageType(original.getMessageType())
            .correlationId(original.getCorrelationId())
            .payload(original.getPayload())
            .status(newStatus)
            .errorMessage(truncate(errorMessage, MAX_ERROR_CHARS))
            .attemptCount(safeIncrement(original.getAttemptCount()))
            .lastAttemptedAt(LocalDateTime.now())
            .receivedAt(LocalDateTime.now())
            .build();
        return repository.save(replay);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static int safeIncrement(Integer current) {
        return current == null ? 2 : current + 1;
    }
}
