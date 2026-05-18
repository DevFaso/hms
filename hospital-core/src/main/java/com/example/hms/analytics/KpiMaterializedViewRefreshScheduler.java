package com.example.hms.analytics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
 * each matview — V105 provides {@code uk_kpi_*_hospital_day}.
 * CONCURRENTLY also requires the matview to have been refreshed at
 * least once non-concurrently; V105 leaves the views empty
 * post-creation per Postgres semantics, so the first scheduled tick
 * falls back to a plain REFRESH if the CONCURRENTLY form fails with
 * a "matview has not been populated" error.
 *
 * <p>Transaction handling: {@code REFRESH MATERIALIZED VIEW
 * CONCURRENTLY} cannot run inside a transaction block in PostgreSQL.
 * The refresher therefore borrows a JDBC connection from the pool
 * directly (no {@code @Transactional}) and executes each REFRESH
 * with autocommit on.
 *
 * <p>Per-view try/catch keeps a single-view failure (e.g. a long
 * pg_stat_activity lock against one source table) from cancelling
 * the other refreshes in the same tick.
 *
 * <p><b>SQL safety</b>: every {@code executeUpdate} below takes a
 * compile-time string literal. The dispatch is a switch keyed on the
 * static {@link MatviewName} enum, so there is no path by which user-
 * controlled data can reach the SQL string. This is deliberate — an
 * earlier rev concatenated the matview name into the SQL after an
 * allowlist check, which CodeQL and Sonar S2077 still flag because a
 * regex test is not a recognised sanitiser.
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

    /**
     * The three KPI matviews V105 ships. The enum is the source of
     * truth — the SQL strings are inlined in {@link #refreshOne}
     * so each {@code executeUpdate} sees a compile-time literal.
     */
    enum MatviewName {
        DOOR_TO_DOCTOR("clinical.kpi_door_to_doctor_daily"),
        DISPENSE_LEAD_TIME("clinical.kpi_dispense_lead_time_daily"),
        NO_SHOW_RATE("clinical.kpi_no_show_rate_daily");

        private final String qualifiedName;

        MatviewName(String qualifiedName) { this.qualifiedName = qualifiedName; }

        String qualifiedName() { return qualifiedName; }
    }

    /**
     * Convenience list for {@code refreshAll()} to iterate. Order
     * doesn't matter — the three views are independent.
     */
    static final List<MatviewName> ALL_MATVIEWS = List.of(MatviewName.values());

    private final DataSource dataSource;

    public KpiMaterializedViewRefreshScheduler(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Scheduled tick driven by
     * {@code app.analytics.kpi.materialized-views.refresh-interval-ms}
     * (default 5 minutes via the property class). Spring's
     * {@code @Scheduled(fixedRateString = "${...}")} accepts the
     * property at startup; runtime changes require a restart, which
     * is the right granularity for matview cadence.
     */
    @Scheduled(fixedRateString = "${app.analytics.kpi.materialized-views.refresh-interval-ms:300000}")
    public void refreshAll() {
        Instant started = Instant.now();
        int succeeded = 0;
        for (MatviewName matview : ALL_MATVIEWS) {
            try {
                refreshOne(matview);
                succeeded++;
            } catch (RuntimeException ex) {
                log.warn("KPI matview refresh failed for {} — staleness will grow until next tick: {}",
                    matview.qualifiedName(), ex.toString());
            }
        }
        log.info("KPI matview refresh tick — succeeded={}/{}, durationMs={}",
            succeeded, ALL_MATVIEWS.size(),
            Duration.between(started, Instant.now()).toMillis());
    }

    /**
     * One matview refresh. Tries CONCURRENTLY first (no lock on
     * concurrent dashboard reads); falls back to plain REFRESH on
     * the first-tick "has not been populated" path or when the
     * unique index is missing for some reason.
     *
     * <p>Runs OUTSIDE any Spring-managed transaction (no
     * {@code @Transactional} here, and no callsite that wraps this
     * method in one). Borrowing the {@link Connection} from
     * {@link DataSource} directly and forcing autocommit on
     * guarantees PostgreSQL accepts the CONCURRENTLY form, which
     * rejects any in-progress transaction block.
     *
     * <p>The {@code switch} dispatches to a per-matview branch where
     * the SQL is a compile-time literal — no string concat, no
     * Sonar S2077 / CodeQL data-flow surface. Adding a fourth
     * matview means adding a fourth enum constant + branch.
     */
    void refreshOne(MatviewName matview) {
        if (matview == null) {
            throw new IllegalArgumentException("matview is required");
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean priorAutocommit = connection.getAutoCommit();
            if (!priorAutocommit) {
                connection.setAutoCommit(true);
            }
            try {
                executeRefresh(connection, matview);
            } finally {
                if (!priorAutocommit) {
                    connection.setAutoCommit(false);
                }
            }
        } catch (SQLException ex) {
            throw new UncategorizedSQLException(
                "REFRESH MATERIALIZED VIEW", matview.qualifiedName(), ex);
        }
    }

    private static void executeRefresh(Connection connection, MatviewName matview) throws SQLException {
        switch (matview) {
            case DOOR_TO_DOCTOR -> refreshDoorToDoctor(connection);
            case DISPENSE_LEAD_TIME -> refreshDispenseLeadTime(connection);
            case NO_SHOW_RATE -> refreshNoShowRate(connection);
        }
    }

    private static void refreshDoorToDoctor(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(
                "REFRESH MATERIALIZED VIEW CONCURRENTLY clinical.kpi_door_to_doctor_daily");
        } catch (SQLException concurrentEx) {
            logFallback("clinical.kpi_door_to_doctor_daily", concurrentEx);
            try (Statement fallback = connection.createStatement()) {
                fallback.executeUpdate(
                    "REFRESH MATERIALIZED VIEW clinical.kpi_door_to_doctor_daily");
            }
        }
    }

    private static void refreshDispenseLeadTime(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(
                "REFRESH MATERIALIZED VIEW CONCURRENTLY clinical.kpi_dispense_lead_time_daily");
        } catch (SQLException concurrentEx) {
            logFallback("clinical.kpi_dispense_lead_time_daily", concurrentEx);
            try (Statement fallback = connection.createStatement()) {
                fallback.executeUpdate(
                    "REFRESH MATERIALIZED VIEW clinical.kpi_dispense_lead_time_daily");
            }
        }
    }

    private static void refreshNoShowRate(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(
                "REFRESH MATERIALIZED VIEW CONCURRENTLY clinical.kpi_no_show_rate_daily");
        } catch (SQLException concurrentEx) {
            logFallback("clinical.kpi_no_show_rate_daily", concurrentEx);
            try (Statement fallback = connection.createStatement()) {
                fallback.executeUpdate(
                    "REFRESH MATERIALIZED VIEW clinical.kpi_no_show_rate_daily");
            }
        }
    }

    /**
     * First tick after V105 lands populates the matview for the
     * first time — Postgres requires a non-CONCURRENT refresh in
     * that case. The same fallback covers a race where the unique
     * index was dropped post-V105 (operators sometimes regenerate
     * stats); we'll re-try CONCURRENTLY on the next tick.
     */
    private static void logFallback(String name, SQLException concurrentEx) {
        log.info("CONCURRENT refresh failed for {} — falling back to non-concurrent REFRESH ({})",
            name, concurrentEx.getMessage());
    }
}
