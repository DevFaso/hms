package com.example.hms.payload.dto.superadmin;

import lombok.Builder;

import java.util.List;

/**
 * MVP-8c — paged response wrapper for the cross-source audit
 * aggregation. Mirrors the shape of {@link AuditSearchPageDTO} so the
 * frontend can reuse its pagination helper.
 */
@Builder
public record AggregatedAuditPageDTO(
    List<AggregatedAuditEventDTO> content,
    int pageNumber,
    int pageSize,
    long totalElements,
    int totalPages
) { }
