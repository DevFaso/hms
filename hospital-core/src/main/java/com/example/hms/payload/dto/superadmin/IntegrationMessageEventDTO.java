package com.example.hms.payload.dto.superadmin;

import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * MVP-c3 — one row in the Bridges-style message-search result. The
 * payload is included so an operator can confirm what actually crossed
 * the wire; the recorder truncates to 64 KB so this DTO can never be
 * a memory hazard.
 *
 * <p>{@code payloadPurgedAt} is set when the retention sweep erased the
 * content (V177); {@code payload} is then null and the row cannot be
 * replayed. A null payload with no purge stamp is a row that never had one.
 */
@Builder
public record IntegrationMessageEventDTO(
    UUID id,
    String integrationId,
    UUID organizationId,
    IntegrationMessageDirection direction,
    String messageType,
    String correlationId,
    String payload,
    LocalDateTime payloadPurgedAt,
    IntegrationMessageStatus status,
    String errorMessage,
    Integer attemptCount,
    LocalDateTime lastAttemptedAt,
    LocalDateTime receivedAt,
    /** QA D4302: the organisation's name, so the page never shows a bare identifier. */
    String organizationName,
    /**
     * QA D4301: a failed message a later attempt has taken over (it was replayed,
     * or the sender's retry got through). It is no longer counted as waiting and
     * cannot be replayed again. Always false for a message that did not fail.
     */
    boolean resolved,
    /** QA D4302: every row of the same exchange, oldest first. Detail view only; null in a list. */
    List<Attempt> attempts,
    /**
     * How many rows the exchange has. {@code attempts} is a preview of its first
     * {@value #ATTEMPTS_PREVIEW}; the page says so when this is larger. Null in a list.
     */
    Long attemptsTotal
) {
    /** The rows of an exchange the detail view lists. */
    public static final int ATTEMPTS_PREVIEW = 50;

    /** One row of an exchange: the original, a sender's retry or an operator's replay. */
    public record Attempt(
        UUID id,
        IntegrationMessageStatus status,
        Integer attemptCount,
        String errorMessage,
        LocalDateTime lastAttemptedAt,
        LocalDateTime receivedAt
    ) { }
}
