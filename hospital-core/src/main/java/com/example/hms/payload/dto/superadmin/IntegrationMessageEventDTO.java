package com.example.hms.payload.dto.superadmin;

import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * MVP-c3 — one row in the Bridges-style message-search result. The
 * payload is included so an operator can confirm what actually crossed
 * the wire; the recorder truncates to 64 KB so this DTO can never be
 * a memory hazard.
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
    IntegrationMessageStatus status,
    String errorMessage,
    Integer attemptCount,
    LocalDateTime lastAttemptedAt,
    LocalDateTime receivedAt
) { }
