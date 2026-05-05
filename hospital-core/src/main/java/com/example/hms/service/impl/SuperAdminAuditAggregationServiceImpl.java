package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
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

    /**
     * Page-size ceiling — clamps user-supplied {@code pageable.pageSize}
     * before any arithmetic. Aligned with {@link #PER_SOURCE_HARD_CAP}
     * so {@code offset + pageSize} for a deep page (pageNumber = cap-1)
     * stays representable as an int. Copilot review fix.
     */
    private static final int MAX_PAGE_SIZE = PER_SOURCE_HARD_CAP;

    /**
     * MVP-c3 — event types that count as platform configuration. The
     * aggregation service splits each {@code AuditEventLog} row to
     * either {@link AuditSource#PLATFORM_CONFIG} (in this set) or
     * {@link AuditSource#SUPPORT} (everything else). Adding an event
     * type here makes it appear under the platform-config tab; the
     * row itself is still persisted exactly once in the support
     * audit_event_logs table.
     */
    static final Set<AuditEventType> PLATFORM_CONFIG_EVENT_TYPES = EnumSet.of(
        AuditEventType.SECURITY_POLICY_UPDATED,
        AuditEventType.CONFIGURATION_CHANGED,
        AuditEventType.API_KEY_CREATED,
        AuditEventType.API_KEY_REVOKED,
        AuditEventType.INTEGRATION_CONFIGURED,
        AuditEventType.PLATFORM_REGISTRY_UPDATED,
        AuditEventType.REGION_POLICY_UPDATED
    );

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
        // Sonar fix — the prior single-method implementation came in at
        // cognitive complexity 21. Per-source fetches are now extracted
        // into helpers so the main method stays a thin orchestrator.
        Set<AuditSource> effectiveSources = effectiveSources(sources);
        int pageNumber = Math.max(0, pageable.getPageNumber());
        int pageSize = Math.clamp(pageable.getPageSize(), 1, MAX_PAGE_SIZE);
        long offset = (long) pageNumber * pageSize;
        int perSourceLimit = (int) Math.min(offset + pageSize, (long) PER_SOURCE_HARD_CAP);

        List<AggregatedAuditEventDTO> merged = new ArrayList<>();
        long totalElements = 0L;
        totalElements += fetchAuditEventLogRows(effectiveSources, fromDate, toDate, perSourceLimit, merged);
        totalElements += fetchFrontendRows(effectiveSources, fromDate, toDate, perSourceLimit, merged);
        totalElements += fetchPermissionMatrixRows(effectiveSources, fromDate, toDate, perSourceLimit, merged);

        // Merge sort by timestamp DESC, nulls last so a row with a
        // missing timestamp doesn't poison the head of the page.
        merged.sort(Comparator.comparing(AggregatedAuditEventDTO::timestamp,
            Comparator.nullsLast(Comparator.reverseOrder())));

        return slice(merged, totalElements, pageNumber, pageSize, offset);
    }

    private static Set<AuditSource> effectiveSources(Set<AuditSource> sources) {
        return (sources == null || sources.isEmpty())
            ? EnumSet.allOf(AuditSource.class)
            : EnumSet.copyOf(sources);
    }

    /**
     * SUPPORT and PLATFORM_CONFIG share the audit_event_logs table —
     * the split is by eventType so a single row never appears under
     * both. Three cases:
     *   - both selected: query everything in date range
     *   - SUPPORT only: query everything NOT in the platform-config set
     *   - PLATFORM_CONFIG only: query everything IN the platform-config set
     */
    private long fetchAuditEventLogRows(
        Set<AuditSource> effectiveSources, LocalDateTime fromDate, LocalDateTime toDate,
        int perSourceLimit, List<AggregatedAuditEventDTO> merged
    ) {
        boolean wantSupport = effectiveSources.contains(AuditSource.SUPPORT);
        boolean wantPlatformConfig = effectiveSources.contains(AuditSource.PLATFORM_CONFIG);
        if (!wantSupport && !wantPlatformConfig) {
            return 0L;
        }
        Pageable supportPage = PageRequest.of(0, perSourceLimit,
            Sort.by(Sort.Direction.DESC, "eventTimestamp"));
        Page<AuditEventLog> page = queryAuditEventLog(
            wantSupport, wantPlatformConfig, fromDate, toDate, supportPage);
        for (AuditEventLog row : page.getContent()) {
            merged.add(toDto(row));
        }
        return Math.min(page.getTotalElements(), (long) PER_SOURCE_HARD_CAP);
    }

    private Page<AuditEventLog> queryAuditEventLog(
        boolean wantSupport, boolean wantPlatformConfig,
        LocalDateTime fromDate, LocalDateTime toDate, Pageable supportPage
    ) {
        if (wantSupport && wantPlatformConfig) {
            return auditEventLogRepository.findByDateRange(fromDate, toDate, supportPage);
        }
        if (wantPlatformConfig) {
            return auditEventLogRepository.findByDateRangeAndEventTypeIn(
                fromDate, toDate, PLATFORM_CONFIG_EVENT_TYPES, supportPage);
        }
        return auditEventLogRepository.findByDateRangeAndEventTypeNotIn(
            fromDate, toDate, PLATFORM_CONFIG_EVENT_TYPES, supportPage);
    }

    private long fetchFrontendRows(
        Set<AuditSource> effectiveSources, LocalDateTime fromDate, LocalDateTime toDate,
        int perSourceLimit, List<AggregatedAuditEventDTO> merged
    ) {
        if (!effectiveSources.contains(AuditSource.FRONTEND)) {
            return 0L;
        }
        Pageable feLimit = PageRequest.of(0, perSourceLimit);
        for (FrontendAuditEvent row : frontendAuditEventRepository
            .findInDateRangeOrdered(fromDate, toDate, feLimit)) {
            merged.add(toDto(row));
        }
        long sourceCount = frontendAuditEventRepository.countInDateRange(fromDate, toDate);
        return Math.min(sourceCount, (long) PER_SOURCE_HARD_CAP);
    }

    private long fetchPermissionMatrixRows(
        Set<AuditSource> effectiveSources, LocalDateTime fromDate, LocalDateTime toDate,
        int perSourceLimit, List<AggregatedAuditEventDTO> merged
    ) {
        if (!effectiveSources.contains(AuditSource.PERMISSION_MATRIX)) {
            return 0L;
        }
        // PermissionMatrixAuditEvent.createdAt is an Instant; convert
        // the LocalDateTime bounds to UTC for a consistent comparison.
        Instant fromInstant = fromDate == null ? null : fromDate.toInstant(ZoneOffset.UTC);
        Instant toInstant = toDate == null ? null : toDate.toInstant(ZoneOffset.UTC);
        Pageable pmLimit = PageRequest.of(0, perSourceLimit);
        for (PermissionMatrixAuditEvent row : permissionMatrixAuditEventRepository
            .findInDateRangeOrdered(fromInstant, toInstant, pmLimit)) {
            merged.add(toDto(row));
        }
        long sourceCount = permissionMatrixAuditEventRepository.countInDateRange(fromInstant, toInstant);
        return Math.min(sourceCount, (long) PER_SOURCE_HARD_CAP);
    }

    private static AggregatedAuditPageDTO slice(
        List<AggregatedAuditEventDTO> merged, long totalElements,
        int pageNumber, int pageSize, long offset
    ) {
        // Cast to int is safe because offset is bounded by pageNumber * MAX_PAGE_SIZE.
        int fromIdx = (int) Math.min(offset, (long) merged.size());
        int toIdx = (int) Math.min(offset + pageSize, (long) merged.size());
        List<AggregatedAuditEventDTO> pageContent = merged.subList(fromIdx, toIdx);
        int totalPages = (int) Math.ceil((double) totalElements / pageSize);
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
        // MVP-c3 — split rows by eventType so platform-config writes
        // surface under their own source rather than drowning in the
        // generic SUPPORT stream.
        AuditSource source = row.getEventType() != null
            && PLATFORM_CONFIG_EVENT_TYPES.contains(row.getEventType())
                ? AuditSource.PLATFORM_CONFIG
                : AuditSource.SUPPORT;
        return AggregatedAuditEventDTO.builder()
            .source(source)
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
