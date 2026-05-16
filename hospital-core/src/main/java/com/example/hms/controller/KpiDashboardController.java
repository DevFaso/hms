package com.example.hms.controller;

import com.example.hms.payload.dto.analytics.KpiDashboardDTO;
import com.example.hms.service.KpiDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Per-hospital operational throughput KPI rollup
 * (roadmap row 32, v1.1 / Reporting).
 *
 * <p>Surfaces three care-delivery indicators (door-to-doctor, dispense
 * lead time, no-show rate) for the date window supplied by the
 * caller. Tenant scope is implicit — the service reads
 * {@code HospitalContextHolder} and uses the active hospital id.
 *
 * <p>Access: any authenticated user with a hospital-context-bearing
 * role (matches the existing analytics surface in
 * {@code SuperAdminDashboardController}). The dashboard intentionally
 * does not expose patient-level rows — only aggregates — so the
 * read does not require {@code PATIENT_ACCESS} audit emission.
 */
@RestController
@RequestMapping(value = "/kpi", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "KPI dashboard", description = "Operational care-delivery KPIs (row 32)")
public class KpiDashboardController {

    private static final int MAX_WINDOW_DAYS = 180;

    private final KpiDashboardService kpiDashboardService;

    public KpiDashboardController(KpiDashboardService kpiDashboardService) {
        this.kpiDashboardService = kpiDashboardService;
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'NURSE', 'STAFF')")
    @Operation(summary = "Compute the door-to-doctor, dispense lead time, and no-show KPIs for a window")
    public ResponseEntity<KpiDashboardDTO> dashboard(
        @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam("to")   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        if (to.isBefore(from)) {
            return ResponseEntity.badRequest().build();
        }
        if (from.plusDays(MAX_WINDOW_DAYS).isBefore(to)) {
            // Cap at 180 days. Larger ranges go through the analytics
            // export pipeline, not the live dashboard.
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(kpiDashboardService.computeDashboard(from, to));
    }
}
