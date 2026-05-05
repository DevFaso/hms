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
        Set<AuditSource> effectiveSources = (sources == null || sources.isEmpty())
            ? EnumSet.allOf(AuditSource.class)
            : EnumSet.copyOf(sources);

        int pageNumber = Math.max(0, pageable.getPageNumber());
        // Copilot review fix — clamp pageSize before any arithmetic so
        // user-controlled values cannot drive an int overflow on offset
        // or perSourceLimit.
        int pageSize = Math.max(1, Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));
        // long arithmetic for offset; offset can theoretically be
        // pageNumber * MAX_PAGE_SIZE which still fits int, but using
        // long keeps the intermediate safe even if the constants change.
        long offset = (long) pageNumber * pageSize;
        // Top (offset + size) from each source — worst case all from one.
        // Hard-capped so a deep-page request can't stampede the DB.
        int perSourceLimit = (int) Math.min(offset + pageSize, (long) PER_SOURCE_HARD_CAP);

        List<AggregatedAuditEventDTO> merged = new ArrayList<>();
        // Copilot review fix — cap each source's count at PER_SOURCE_HARD_CAP
        // so totalElements / totalPages reflect the *retrievable* row count,
        // not the database-level COUNT(*). Otherwise a client paginating
        // past the cap would see "page 12 of 50" but get an empty body
        // because the service can't fetch deep enough.
        long totalElements = 0L;

        // SUPPORT and PLATFORM_CONFIG share the audit_event_logs table —
        // the split is by eventType so a single row never appears under
        // both. Three cases:
        //   - both selected: query everything in date range
        //   - SUPPORT only: query everything NOT in the platform-config set
        //   - PLATFORM_CONFIG only: query everything IN the platform-config set
        boolean wantSupport = effectiveSources.contains(AuditSource.SUPPORT);
        boolean wantPlatformConfig = effectiveSources.contains(AuditSource.PLATFORM_CONFIG);
        if (wantSupport || wantPlatformConfig) {
            Pageable supportPage = PageRequest.of(0, perSourceLimit,
                Sort.by(Sort.Direction.DESC, "eventTimestamp"));
            Page<AuditEventLog> page;
            if (wantSupport && wantPlatformConfig) {
                page = auditEventLogRepository.findByDateRange(fromDate, toDate, supportPage);
            } else if (wantPlatformConfig) {
                page = auditEventLogRepository.findByDateRangeAndEventTypeIn(
                    fromDate, toDate, PLATFORM_CONFIG_EVENT_TYPES, supportPage);
            } else {
                page = auditEventLogRepository.findByDateRangeAndEventTypeNotIn(
                    fromDate, toDate, PLATFORM_CONFIG_EVENT_TYPES, supportPage);
            }
            for (AuditEventLog row : page.getContent()) {
                merged.add(toDto(row));
            }
            totalElements += Math.min(page.getTotalElements(), (long) PER_SOURCE_HARD_CAP);
        }

        if (effectiveSources.contains(AuditSource.FRONTEND)) {
            Pageable feLimit = PageRequest.of(0, perSourceLimit);
            List<FrontendAuditEvent> rows = frontendAuditEventRepository
                .findInDateRangeOrdered(fromDate, toDate, feLimit);
            for (FrontendAuditEvent row : rows) {
                merged.add(toDto(row));
            }
            long sourceCount = frontendAuditEventRepository.countInDateRange(fromDate, toDate);
            totalElements += Math.min(sourceCount, (long) PER_SOURCE_HARD_CAP);
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
            long sourceCount = permissionMatrixAuditEventRepository.countInDateRange(fromInstant, toInstant);
            totalElements += Math.min(sourceCount, (long) PER_SOURCE_HARD_CAP);
        }

        // Merge sort by timestamp DESC, nulls last so a row with a
        // missing timestamp doesn't poison the head of the page.
        merged.sort(Comparator.comparing(AggregatedAuditEventDTO::timestamp,
            Comparator.nullsLast(Comparator.reverseOrder())));

        // Slice the requested page out of the merged stream. Cast to int
        // is safe because offset is bounded by pageNumber * MAX_PAGE_SIZE.
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
