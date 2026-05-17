package com.example.hms.controller;

import com.example.hms.observability.ChargebackReportService;
import com.example.hms.observability.ChargebackReportService.TenantCostRow;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Super-admin per-tenant chargeback report (roadmap row 44, v2.0 /
 * Operations).
 *
 * <p>Gated by
 * {@code app.observability.tenant-cost.enabled} (default false). When
 * the flag is off the endpoint returns {@code 404 Not Found} so the
 * shape does not leak before the rollup is operationally meaningful;
 * authentication is still enforced (anonymous requests return 401
 * from Spring Security ahead of the flag check).
 *
 * <p>Foundation-pass input is the per-hospital count of audit events
 * over the requested window. The deliverable target adds Splunk event
 * counts, Grafana series cardinality, and a per-deployment cost-model
 * mapping to currency-amount — those are the named row-44 follow-on.
 */
@RestController
@RequestMapping("/super-admin/cost")
public class ChargebackReportController {

    private static final int MAX_WINDOW_DAYS = 92;

    private final ChargebackReportService service;

    public ChargebackReportController(ChargebackReportService service) {
        this.service = service;
    }

    /**
     * GET /api/super-admin/cost/per-tenant?from=YYYY-MM-DD&to=YYYY-MM-DD.
     * Inclusive {@code [from, to]} window; defaults to the trailing
     * 30 days when both params are absent. Window length is capped at
     * {@link #MAX_WINDOW_DAYS} to bound the query.
     */
    @GetMapping("/per-tenant")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<TenantCostRow>> perTenant(
        @RequestParam(value = "from", required = false) String fromRaw,
        @RequestParam(value = "to", required = false) String toRaw
    ) {
        if (!service.isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        LocalDate toDate = parseOrDefault(toRaw, LocalDate.now());
        LocalDate fromDate = parseOrDefault(fromRaw, toDate.minusDays(30));
        if (fromDate.isAfter(toDate)) {
            return ResponseEntity.badRequest().build();
        }
        if (fromDate.plusDays(MAX_WINDOW_DAYS - 1L).isBefore(toDate)) {
            return ResponseEntity.badRequest().build();
        }
        LocalDateTime fromTs = fromDate.atStartOfDay();
        LocalDateTime toTs = toDate.atTime(LocalTime.MAX);
        return ResponseEntity.ok(service.auditEventCountsPerTenant(fromTs, toTs));
    }

    private static LocalDate parseOrDefault(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return LocalDate.parse(raw.trim());
        } catch (RuntimeException ex) {
            return fallback;
        }
    }
}
