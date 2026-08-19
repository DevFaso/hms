package com.example.hms.controller;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditSource;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.OrganizationRegion;
import com.example.hms.payload.dto.superadmin.AggregatedAuditPageDTO;
import com.example.hms.payload.dto.superadmin.AuditSearchFilter;
import com.example.hms.payload.dto.superadmin.AuditSearchPageDTO;
import com.example.hms.service.SuperAdminAuditAggregationService;
import com.example.hms.service.SuperAdminAuditSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * MVP-8: Cross-tenant audit search for super admins. All filter params are
 * optional; results are paged and sorted by eventTimestamp desc by default.
 */
@RestController
@RequestMapping("/super-admin/audit-search")
@RequiredArgsConstructor
@Tag(name = "Super Admin Audit Search",
    description = "Cross-tenant audit search across AuditEventLog with impersonator filter (MVP-8 in docs/super-admin-gaps.md)")
@SecurityRequirement(name = "bearerAuth")
public class SuperAdminAuditSearchController {

    private final SuperAdminAuditSearchService service;
    private final SuperAdminAuditAggregationService aggregationService;

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Cross-tenant search of AuditEventLog with optional filters")
    public ResponseEntity<AuditSearchPageDTO> search(
        @RequestParam(required = false) UUID userId,
        @RequestParam(required = false) String userName,
        @RequestParam(required = false) List<AuditEventType> eventTypes,
        @RequestParam(required = false) AuditStatus status,
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(required = false) UUID organizationId,
        @RequestParam(required = false) UUID impersonatorUserId,
        @RequestParam(required = false) String entityType,
        @RequestParam(required = false) String resourceId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
        // MVP-9b — narrow the search to one regulatory region; joins
        // through assignment.hospital.organization.region in the service.
        @RequestParam(required = false) OrganizationRegion tenantRegion,
        Pageable pageable
    ) {
        AuditSearchFilter filter = new AuditSearchFilter(
            userId, userName, eventTypes, status, hospitalId, organizationId,
            impersonatorUserId, entityType, resourceId, fromDate, toDate, tenantRegion);
        return ResponseEntity.ok(service.search(filter, pageable));
    }

    /**
     * MVP-8b — same filter set as the JSON endpoint, rendered as CSV.
     * Returns up to {@code maxRows} rows (clamped server-side to 50 000)
     * sorted by eventTimestamp desc, with a Content-Disposition header
     * that triggers a browser download.
     */
    @GetMapping(value = "/csv", produces = "text/csv")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Cross-tenant audit search export to CSV (same filters as the JSON endpoint)")
    public ResponseEntity<byte[]> exportCsv(
        @RequestParam(required = false) UUID userId,
        @RequestParam(required = false) String userName,
        @RequestParam(required = false) List<AuditEventType> eventTypes,
        @RequestParam(required = false) AuditStatus status,
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(required = false) UUID organizationId,
        @RequestParam(required = false) UUID impersonatorUserId,
        @RequestParam(required = false) String entityType,
        @RequestParam(required = false) String resourceId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
        @RequestParam(required = false) OrganizationRegion tenantRegion,
        @RequestParam(name = "maxRows", required = false, defaultValue = "10000") int maxRows
    ) {
        AuditSearchFilter filter = new AuditSearchFilter(
            userId, userName, eventTypes, status, hospitalId, organizationId,
            impersonatorUserId, entityType, resourceId, fromDate, toDate, tenantRegion);
        byte[] csv = service.exportCsv(filter, maxRows);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv"))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"audit-search.csv\"")
            .body(csv);
    }

    /**
     * MVP-8c — cross-source audit aggregation. Unions
     * {@code support.audit_event_logs}, {@code frontend_audit_events},
     * and {@code permission_matrix_audit_events} into one merged feed
     * sorted by event timestamp DESC. {@code sources} is optional —
     * empty/missing means "all three". Date bounds are optional but
     * recommended; the service caps per-source rows to keep deep-page
     * requests bounded.
     */
    @GetMapping("/aggregated")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Cross-source audit aggregation across SUPPORT / FRONTEND / PERMISSION_MATRIX")
    public ResponseEntity<AggregatedAuditPageDTO> searchAggregated(
        @RequestParam(required = false) List<AuditSource> sources,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
        Pageable pageable
    ) {
        Set<AuditSource> sourceSet = (sources == null || sources.isEmpty())
            ? EnumSet.allOf(AuditSource.class)
            : EnumSet.copyOf(sources);
        return ResponseEntity.ok(
            aggregationService.searchAggregated(sourceSet, fromDate, toDate, pageable));
    }
}
