package com.example.hms.payload.dto.superadmin;

import lombok.Builder;

import java.util.List;

/**
 * MVP-c3 — paged search result for the message-trace UI.
 * {@code payloadRetentionDays} is the configured content-retention window
 * ({@code hms.integration.retention.payload-days}), so the page can say how
 * long content is kept and why a purged row has none.
 */
@Builder
public record IntegrationMessagePageDTO(
    List<IntegrationMessageEventDTO> content,
    int pageNumber,
    int pageSize,
    long totalElements,
    int totalPages,
    long deadLetterCount,
    int payloadRetentionDays
) { }
