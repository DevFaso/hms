package com.example.hms.payload.dto.superadmin;

import lombok.Builder;

import java.util.List;

/**
 * MVP-c3 — paged search result for the message-trace UI.
 * {@code payloadRetentionDays} and {@code payloadUnresolvedMaxDays} are the
 * configured content-retention window and the ceiling for unresolved dead
 * letters ({@code hms.integration.retention.payload-days} /
 * {@code unresolved-max-days}), so the page can state the policy.
 * {@code retentionActive} is false when the sweep is disabled or refuses its
 * configuration; the windows are then not being enforced and the page says
 * retention is off instead of quoting them.
 */
@Builder
public record IntegrationMessagePageDTO(
    List<IntegrationMessageEventDTO> content,
    int pageNumber,
    int pageSize,
    long totalElements,
    int totalPages,
    long deadLetterCount,
    boolean retentionActive,
    int payloadRetentionDays,
    int payloadUnresolvedMaxDays
) { }
