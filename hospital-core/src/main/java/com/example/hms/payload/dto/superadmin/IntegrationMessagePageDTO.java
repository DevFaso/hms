package com.example.hms.payload.dto.superadmin;

import lombok.Builder;

import java.util.List;

/** MVP-c3 — paged search result for the message-trace UI. */
@Builder
public record IntegrationMessagePageDTO(
    List<IntegrationMessageEventDTO> content,
    int pageNumber,
    int pageSize,
    long totalElements,
    int totalPages,
    long deadLetterCount
) { }
