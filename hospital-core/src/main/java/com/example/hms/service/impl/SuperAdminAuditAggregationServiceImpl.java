package com.example.hms.service.impl;

import com.example.hms.enums.AuditSource;
import com.example.hms.model.AuditEventLog;
import com.example.hms.model.FrontendAuditEvent;
import com.example.hms.model.PermissionMatrixAuditEvent;
import com.example.hms.payload.dto.superadmin.AggregatedAuditEventDTO;
import com.example.hms.payload.dto.superadmin.AggregatedAuditPageDTO;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.FrontendAuditEventRepository;
import com.example.hms.repository.PermissionMatrixAuditEventRepository;
import com.example.hms.service.SuperAdminAuditAggregationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * MVP-8c — cross-source audit aggregation.
 *
 * <p>Strategy: for the requested page (offset {@code o}, size {@code s})
 * the worst case is that all {@code o + s} entries come from a single
 * source, so we fetch the top {@code o + s} rows from each requested
 * source, normalise them to {@link AggregatedAuditEventDTO}, merge by
 * timestamp DESC, then drop the first {@code o} entries and take
 * {@code s}. Per-source row caps prevent runaway queries.
 *
 * <p>Total elements is the sum of per-source counts under the same
 * date filter — exact for pagination math, no extra heap-merge cost.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SuperAdminAuditAggregationServiceImpl implements SuperAdminAuditAggregationService {

    /** Hard cap per source so a wide-open page request can't materialise millions of rows. */
    private static final int PER_SOURCE_HARD_CAP = 5_000;

    private final AuditEventLogRepository auditEventLogRepository;
    private final FrontendAuditEventRepository frontendAuditEventRepository;
    private final PermissionMatrixAuditEventRepository permissionMatrixAuditEventRepository;

    @Override
    public AggregatedAuditPageDTO searchAggregated(
        Set<AuditSource> sources,
        LocalDateTime fromDate,
        LocalDateTime toDate,
        Pageable pageable
    ) {
        Set<AuditSource> effectiveSources = (sources == null || sources.isEmpty())
            ? EnumSet.allOf(AuditSource.class)
            : EnumSet.copyOf(sources);

        int pageNumber = Math.max(0, pageable.getPageNumber());
        int pageSize = Math.max(1, pageable.getPageSize());
        int offset = pageNumber * pageSize;
        // Top (offset + size) from each source — worst case all from one.
        // Hard-capped so a deep-page request can't stampede the DB.
        int perSourceLimit = Math.min(offset + pageSize, PER_SOURCE_HARD_CAP);

        List<AggregatedAuditEventDTO> merged = new ArrayList<>();
        long totalElements = 0L;

        if (effectiveSources.contains(AuditSource.SUPPORT)) {
            Pageable supportPage = PageRequest.of(0, perSourceLimit,
                Sort.by(Sort.Direction.DESC, "eventTimestamp"));
            Page<AuditEventLog> page = auditEventLogRepository.findByDateRange(fromDate, toDate, supportPage);
            for (AuditEventLog row : page.getContent()) {
                merged.add(toDto(row));
            }
            totalElements += page.getTotalElements();
        }

        if (effectiveSources.contains(AuditSource.FRONTEND)) {
            Pageable feLimit = PageRequest.of(0, perSourceLimit);
            List<FrontendAuditEvent> rows = frontendAuditEventRepository
                .findInDateRangeOrdered(fromDate, toDate, feLimit);
            for (FrontendAuditEvent row : rows) {
                merged.add(toDto(row));
            }
            totalElements += frontendAuditEventRepository.countInDateRange(fromDate, toDate);
        }

        if (effectiveSources.contains(AuditSource.PERMISSION_MATRIX)) {
            // PermissionMatrixAuditEvent.createdAt is an Instant; convert
            // the LocalDateTime bounds to UTC for a consistent comparison.
            Instant fromInstant = fromDate == null ? null : fromDate.toInstant(ZoneOffset.UTC);
            Instant toInstant = toDate == null ? null : toDate.toInstant(ZoneOffset.UTC);
            Pageable pmLimit = PageRequest.of(0, perSourceLimit);
            List<PermissionMatrixAuditEvent> rows = permissionMatrixAuditEventRepository
                .findInDateRangeOrdered(fromInstant, toInstant, pmLimit);
            for (PermissionMatrixAuditEvent row : rows) {
                merged.add(toDto(row));
            }
            totalElements += permissionMatrixAuditEventRepository.countInDateRange(fromInstant, toInstant);
        }

        // Merge sort by timestamp DESC, nulls last so a row with a
        // missing timestamp doesn't poison the head of the page.
        merged.sort(Comparator.comparing(AggregatedAuditEventDTO::timestamp,
            Comparator.nullsLast(Comparator.reverseOrder())));

        // Slice the requested page out of the merged stream.
        int fromIdx = Math.min(offset, merged.size());
        int toIdx = Math.min(offset + pageSize, merged.size());
        List<AggregatedAuditEventDTO> pageContent = merged.subList(fromIdx, toIdx);

        int totalPages = pageSize == 0 ? 0 : (int) Math.ceil((double) totalElements / pageSize);

        return AggregatedAuditPageDTO.builder()
            .content(List.copyOf(pageContent))
            .pageNumber(pageNumber)
            .pageSize(pageSize)
            .totalElements(totalElements)
            .totalPages(totalPages)
            .build();
    }

    // ── per-source mappers ──────────────────────────────────────────────

    private AggregatedAuditEventDTO toDto(AuditEventLog row) {
        UUID hospitalId = row.getAssignment() != null && row.getAssignment().getHospital() != null
            ? row.getAssignment().getHospital().getId()
            : null;
        UUID organizationId = row.getAssignment() != null
                && row.getAssignment().getHospital() != null
                && row.getAssignment().getHospital().getOrganization() != null
            ? row.getAssignment().getHospital().getOrganization().getId()
            : null;
        return AggregatedAuditEventDTO.builder()
            .source(AuditSource.SUPPORT)
            .id(row.getId())
            .eventType(row.getEventType() == null ? null : row.getEventType().name())
            .actor(row.getUserName())
            .hospitalId(hospitalId)
            .organizationId(organizationId)
            .status(row.getStatus() == null ? null : row.getStatus().name())
            .timestamp(row.getEventTimestamp())
            .summary(row.getEventDescription())
            .build();
    }

    private AggregatedAuditEventDTO toDto(FrontendAuditEvent row) {
        return AggregatedAuditEventDTO.builder()
            .source(AuditSource.FRONTEND)
            .id(row.getId())
            .eventType(row.getEventType())
            .actor(row.getActor())
            .timestamp(row.getOccurredAt())
            .summary(row.getMetadata())
            .build();
    }

    private AggregatedAuditEventDTO toDto(PermissionMatrixAuditEvent row) {
        return AggregatedAuditEventDTO.builder()
            .source(AuditSource.PERMISSION_MATRIX)
            .id(row.getId())
            .eventType(row.getAction() == null ? null : row.getAction().name())
            .actor(row.getInitiatedBy())
            .timestamp(row.getCreatedAt() == null
                ? null
                : LocalDateTime.ofInstant(row.getCreatedAt(), ZoneOffset.UTC))
            .summary(row.getDescription())
            .build();
    }
}
