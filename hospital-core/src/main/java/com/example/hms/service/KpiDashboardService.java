package com.example.hms.service;

import com.example.hms.payload.dto.analytics.KpiDashboardDTO;

import java.time.LocalDate;

/**
 * Per-hospital operational throughput KPI rollup
 * (roadmap row 32, v1.1 / Reporting / "KPI dashboard service").
 *
 * <p>The implementation reads the current {@code HospitalContextHolder}
 * to scope its three queries; super-admin callers (no
 * {@code activeHospitalId} on the context) receive a tenant-empty
 * response with all sample-sizes at zero — the dashboard must be
 * launched in a specific hospital scope to read meaningful numbers.
 */
public interface KpiDashboardService {

    /**
     * Compute the three care-delivery KPIs for the current hospital
     * context across the given inclusive date window. Both bounds are
     * required; the controller validates the range before delegating.
     */
    KpiDashboardDTO computeDashboard(LocalDate fromInclusive, LocalDate toInclusive);
}
