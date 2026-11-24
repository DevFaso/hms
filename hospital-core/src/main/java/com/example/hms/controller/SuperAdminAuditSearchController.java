package com.example.hms.controller;

import com.example.hms.controller.support.AuditEventTypeParams;
import com.example.hms.controller.support.StrictQueryParams;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditSource;
import com.example.hms.payload.dto.superadmin.AggregatedAuditPageDTO;
import com.example.hms.payload.dto.superadmin.AuditSearchFilter;
import com.example.hms.payload.dto.superadmin.AuditSearchRequestDTO;
import com.example.hms.security.ReadsOnly;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * MVP-8: Cross-tenant audit search for super admins. All filter params are
 * optional; results are paged and sorted by eventTimestamp desc by default.
 */
@RestController
@StrictQueryParams
@RequestMapping("/super-admin/audit-search")
@RequiredArgsConstructor
@Tag(name = "Super Admin Audit Search",
    description = "Cross-tenant audit search across AuditEventLog with impersonator filter (MVP-8 in docs/super-admin-gaps.md)")
@SecurityRequirement(name = "bearerAuth")
public class SuperAdminAuditSearchController {

    private final SuperAdminAuditSearchService service;
    private final SuperAdminAuditAggregationService aggregationService;

    // The filters (a user's login among them) travel in the body, never in the URL.
    @ReadsOnly("an audit search, under POST so its filters stay out of the URL")
    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Cross-tenant search of AuditEventLog with optional filters")
    public ResponseEntity<AuditSearchPageDTO> search(
        // QA D798/D801: eventType and eventTypes are one filter under two names, read by
        // AuditEventTypeParams exactly as /audit-logs reads it (any match).
        // MVP-9b: tenantRegion narrows the search to one regulatory region; joins
        // through assignment.hospital.organization.region in the service.
        @RequestBody(required = false) AuditSearchRequestDTO body,
        Pageable pageable
    ) {
        return ResponseEntity.ok(service.search(filter(body), pageable));
    }

    private static AuditSearchFilter filter(AuditSearchRequestDTO body) {
        AuditSearchRequestDTO f = body != null ? body : AuditSearchRequestDTO.none();
        if (f.unknown() != null && !f.unknown().isEmpty()) {
            // As an unknown query parameter is: a misspelt filter never widens the search.
            String name = new java.util.TreeSet<>(f.unknown().keySet()).first();
            throw new com.example.hms.exception.BusinessException("error.request.unknownParameter",
                name.length() > 64 ? name.substring(0, 64) : name, AuditSearchRequestDTO.ACCEPTED);
        }
        return new AuditSearchFilter(
            f.userId(), f.userName(), eventTypeFilter(f.eventType(), f.eventTypes()), f.status(), f.hospitalId(),
            f.organizationId(), f.impersonatorUserId(), f.entityType(), f.resourceId(), f.fromDate(), f.toDate(),
            f.tenantRegion(), Boolean.TRUE.equals(f.excludeRoutineSuccesses()));
    }

    /**
     * QA D798: {@code eventType} (singular) is the same filter as
     * {@code eventTypes}; both may be given and the row matches any of them.
     * An unknown value is a translated 400 naming it, never every row.
     * {@code null} when none was named: the service reads that as no filter.
     */
    private static List<AuditEventType> eventTypeFilter(List<String> eventType, List<String> eventTypes) {
        Set<AuditEventType> types = AuditEventTypeParams.parse(eventType, eventTypes);
        return types.isEmpty() ? null : List.copyOf(types);
    }

    /**
     * MVP-8b — same filter set as the JSON endpoint, rendered as CSV.
     * Returns up to {@code maxRows} rows (clamped server-side to 50 000)
     * sorted by eventTimestamp desc, with a Content-Disposition header
     * that triggers a browser download.
     */
    // The same filters as the search, in the body; the row cap stays in the query.
    @ReadsOnly("an audit export, under POST so its filters stay out of the URL")
    @PostMapping(value = "/csv", produces = "text/csv")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Cross-tenant audit search export to CSV (same filters as the JSON endpoint)")
    public ResponseEntity<byte[]> exportCsv(
        @RequestBody(required = false) AuditSearchRequestDTO body,
        @RequestParam(name = "maxRows", required = false, defaultValue = "10000") int maxRows
    ) {
        AuditSearchFilter filter = filter(body);
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
