package com.example.hms.service.analytics;

import com.example.hms.payload.dto.analytics.MorbidityDashboardDTO;

import java.time.YearMonth;

/** Top-diagnoses aggregation behind the morbidity surveillance dashboard. */
public interface MorbidityAnalyticsService {

    /**
     * Rank the diagnoses recorded in {@code month}.
     *
     * <p>Scope follows the CALLER, not a parameter: a SUPER_ADMIN gets
     * every hospital plus the per-hospital split; anyone else gets their
     * own active hospital and an empty split. There is deliberately no
     * {@code hospitalId} argument — a caller cannot ask for a tenant they
     * are not in.
     *
     * @param month the calendar month to count
     * @param limit maximum ranked rows (per hospital, and overall)
     */
    MorbidityDashboardDTO topDiagnoses(YearMonth month, int limit);
}
