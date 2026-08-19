package com.example.hms.controller;

import com.example.hms.observability.ChargebackReportService;
import com.example.hms.observability.ChargebackReportService.TenantCostRow;
import com.example.hms.observability.ChargebackReportService.TenantCostRowV2;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
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
        Window window = parseWindow(fromRaw, toRaw);
        return ResponseEntity.ok(service.auditEventCountsPerTenant(window.from(), window.to()));
    }

    /**
     * Row-44 follow-on endpoint: per-tenant chargeback rollup with
     * stable {@code hospitalId} key + configured cost-model amounts.
     * Same flag + window + window-cap contract as the foundation
     * endpoint above. Returns {@code 404} when the flag is off so
     * the shape stays hidden until the operator has set the rates
     * on {@code TenantCostModelProperties}.
     */
    @GetMapping("/per-tenant/chargeback")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<TenantCostRowV2>> chargebackPerTenant(
        @RequestParam(value = "from", required = false) String fromRaw,
        @RequestParam(value = "to", required = false) String toRaw
    ) {
        if (!service.isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        Window window = parseWindow(fromRaw, toRaw);
        return ResponseEntity.ok(service.chargebackPerTenant(window.from(), window.to()));
    }

    /**
     * Parse + validate the {@code from} / {@code to} query params into
     * an inclusive {@link Window}. Malformed input throws
     * {@code 400 Bad Request} — silent defaulting on bad input would
     * return a successful report for the wrong window, which is the
     * input-validation lesson from PR #352 Copilot review on this
     * exact controller's {@code parseOrDefault}.
     */
    private static Window parseWindow(String fromRaw, String toRaw) {
        LocalDate toDate = parseDateOrThrow(toRaw, "to", LocalDate.now());
        LocalDate fromDate = parseDateOrThrow(fromRaw, "from", toDate.minusDays(30));
        if (fromDate.isAfter(toDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "from (" + fromDate + ") must be on or before to (" + toDate + ")");
        }
        if (fromDate.plusDays(MAX_WINDOW_DAYS - 1L).isBefore(toDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "window exceeds the " + MAX_WINDOW_DAYS + "-day cap");
        }
        return new Window(fromDate.atStartOfDay(), toDate.atTime(LocalTime.MAX));
    }

    private static LocalDate parseDateOrThrow(String raw, String paramName, LocalDate fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "query parameter '" + paramName + "' must be ISO-8601 (YYYY-MM-DD): " + raw);
        }
    }

    private record Window(LocalDateTime from, LocalDateTime to) {}
}
