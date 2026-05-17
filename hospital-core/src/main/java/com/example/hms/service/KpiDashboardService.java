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
     *
     * <p>Equivalent to {@code computeDashboard(from, to, false)} — the
     * {@code trend} field on the response is {@code null}.
     */
    default KpiDashboardDTO computeDashboard(LocalDate fromInclusive, LocalDate toInclusive) {
        return computeDashboard(fromInclusive, toInclusive, false);
    }

    /**
     * Compute the three care-delivery KPIs and, when
     * {@code withTrends} is true, the per-day timeseries used by the
     * UI sparklines. The trend computation runs three extra
     * group-by-day queries; foundation pass requires it to be opt-in
     * so the existing GET /kpi/dashboard callers stay on the
     * three-query path.
     */
    KpiDashboardDTO computeDashboard(LocalDate fromInclusive, LocalDate toInclusive, boolean withTrends);
}
