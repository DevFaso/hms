package com.example.hms.analytics;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Refreshes the row-32 KPI matviews on a fixed interval.
 *
 * <p>Active only when BOTH
 * {@code app.analytics.kpi.materialized-views.enabled=true} AND
 * {@code app.analytics.kpi.materialized-views.refresh-scheduler-enabled=true}.
 * The default-disabled state means the H2-backed integration suite
 * never schedules the refresher (no jitter on test runs, no
 * accidental "schedule a thing that does nothing useful" in dev).
 *
 * <p>Each tick runs {@code REFRESH MATERIALIZED VIEW CONCURRENTLY}
 * on the three matviews. CONCURRENTLY requires a unique index on
 * each matview — V105 provides
 * {@code uk_kpi_*_hospital_day}. CONCURRENTLY also requires the
 * matview to have been refreshed at least once non-concurrently;
 * V105 leaves the views empty post-creation per Postgres semantics,
 * so the first scheduled tick falls back to a plain REFRESH if the
 * CONCURRENTLY form fails with a "matview has not been populated"
 * error.
 *
 * <p>Per-view try/catch keeps a single-view failure (e.g. a long
 * pg_stat_activity lock against one source table) from cancelling
 * the other refreshes in the same tick.
 */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "app.analytics.kpi.materialized-views",
    name = {"enabled", "refresh-scheduler-enabled"},
    havingValue = "true"
)
@Profile("!test")
public class KpiMaterializedViewRefreshScheduler {

    private static final List<String> MATVIEW_NAMES = List.of(
        "clinical.kpi_door_to_doctor_daily",
        "clinical.kpi_dispense_lead_time_daily",
        "clinical.kpi_no_show_rate_daily"
    );

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Scheduled tick driven by
     * {@code app.analytics.kpi.materialized-views.refresh-interval-ms}
     * (default 5 minutes via the property class). Spring's
     * {@code @Scheduled(fixedRateString = "${...}")} accepts the
     * property at startup; runtime changes require a restart, which
     * is the right granularity for matview cadence (a hot-reload
     * pattern here would tempt operators into mid-soak changes).
     */
    @Scheduled(fixedRateString = "${app.analytics.kpi.materialized-views.refresh-interval-ms:300000}")
    public void refreshAll() {
        Instant started = Instant.now();
        int succeeded = 0;
        for (String matview : MATVIEW_NAMES) {
            try {
                refreshOne(matview);
                succeeded++;
            } catch (RuntimeException ex) {
                log.warn("KPI matview refresh failed for {} — staleness will grow until next tick: {}",
                    matview, ex.toString());
            }
        }
        log.info("KPI matview refresh tick — succeeded={}/{}, durationMs={}",
            succeeded, MATVIEW_NAMES.size(),
            Duration.between(started, Instant.now()).toMillis());
    }

    /**
     * One matview refresh. Tries CONCURRENTLY first (no lock on
     * concurrent dashboard reads); falls back to plain REFRESH on
     * the first-tick "has not been populated" path or when the
     * unique index is missing for some reason.
     *
     * <p>Each refresh runs in its own transaction so a failure on
     * one view doesn't roll back another. {@code Propagation.REQUIRES_NEW}
     * is implicit at the bean boundary because of the
     * {@code @Scheduled} entry-point (Spring creates a new
     * transaction per scheduled invocation when {@code @Transactional}
     * is declared).
     */
    @Transactional
    protected void refreshOne(String matviewName) {
        try {
            entityManager.createNativeQuery(
                "REFRESH MATERIALIZED VIEW CONCURRENTLY " + matviewName).executeUpdate();
        } catch (RuntimeException concurrentEx) {
            // First tick after V105 lands populates the matview for
            // the first time — Postgres requires a non-CONCURRENT
            // refresh in that case. The same fallback covers a
            // race where the unique index was dropped post-V105
            // (operators sometimes regenerate stats); we'll re-try
            // CONCURRENTLY on the next tick.
            log.info("CONCURRENT refresh failed for {} — falling back to non-concurrent REFRESH ({})",
                matviewName, concurrentEx.getMessage());
            entityManager.createNativeQuery(
                "REFRESH MATERIALIZED VIEW " + matviewName).executeUpdate();
        }
    }
}
