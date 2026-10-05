package com.example.hms.payload.dto.superadmin;

import lombok.Builder;

import java.util.List;

/**
 * MVP-c3 — paged search result for the message-trace UI.
 * {@code payloadRetentionDays} and {@code payloadUnresolvedMaxDays} are the
 * configured content-retention window and the ceiling for unresolved dead
 * letters ({@code hms.integration.retention.payload-days} /
 * {@code unresolved-max-days}), so the page can state the policy.
 */
@Builder
public record IntegrationMessagePageDTO(
    List<IntegrationMessageEventDTO> content,
    int pageNumber,
    int pageSize,
    long totalElements,
    int totalPages,
    long deadLetterCount,
    int payloadRetentionDays,
    int payloadUnresolvedMaxDays
) { }
