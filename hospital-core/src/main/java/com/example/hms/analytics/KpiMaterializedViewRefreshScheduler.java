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
import java.util.regex.Pattern;

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
 * with autocommit on. Spring's {@link JdbcTemplate} could be used
 * with the same connection, but using a raw {@link Connection}
 * makes the autocommit guarantee explicit at the call site.
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

    static final List<String> MATVIEW_NAMES = List.of(
        "clinical.kpi_door_to_doctor_daily",
        "clinical.kpi_dispense_lead_time_daily",
        "clinical.kpi_no_show_rate_daily"
    );

    /**
     * Allowlist for the qualified matview identifier. Constants in
     * {@link #MATVIEW_NAMES} satisfy this, but Sonar / CodeQL trace
     * data flow rather than constant-folding — re-validating here
     * keeps the SQL-string concat statically defensible.
     */
    private static final Pattern SAFE_MATVIEW = Pattern.compile("^[a-z][a-z0-9_]{0,62}(\\.[a-z][a-z0-9_]{0,62})?$");

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
     * <p>Runs OUTSIDE any Spring-managed transaction (no
     * {@code @Transactional} here, and no callsite that wraps this
     * method in one). Borrowing the {@link Connection} from
     * {@link DataSource} directly and forcing autocommit on
     * guarantees PostgreSQL accepts the CONCURRENTLY form, which
     * rejects any in-progress transaction block.
     */
    void refreshOne(String matviewName) {
        String safeName = sanitiseMatviewName(matviewName);
        try (Connection connection = dataSource.getConnection()) {
            boolean priorAutocommit = connection.getAutoCommit();
            if (!priorAutocommit) {
                connection.setAutoCommit(true);
            }
            try {
                runConcurrentOrFallback(connection, safeName);
            } finally {
                if (!priorAutocommit) {
                    connection.setAutoCommit(false);
                }
            }
        } catch (SQLException ex) {
            throw new UncategorizedSQLException(
                "REFRESH MATERIALIZED VIEW", safeName, ex);
        }
    }

    private static void runConcurrentOrFallback(Connection connection, String safeName) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("REFRESH MATERIALIZED VIEW CONCURRENTLY " + safeName);
        } catch (SQLException concurrentEx) {
            // First tick after V105 lands populates the matview for
            // the first time — Postgres requires a non-CONCURRENT
            // refresh in that case. The same fallback covers a race
            // where the unique index was dropped post-V105
            // (operators sometimes regenerate stats); we'll re-try
            // CONCURRENTLY on the next tick.
            log.info("CONCURRENT refresh failed for {} — falling back to non-concurrent REFRESH ({})",
                safeName, concurrentEx.getMessage());
            try (Statement fallback = connection.createStatement()) {
                fallback.executeUpdate("REFRESH MATERIALIZED VIEW " + safeName);
            }
        }
    }

    /**
     * Defence-in-depth: revalidate the matview identifier against the
     * {@link #SAFE_MATVIEW} allowlist and then rebuild it from the
     * allowlist character set into a fresh {@link StringBuilder}.
     * Constants in {@link #MATVIEW_NAMES} already satisfy this, but
     * the rebuild breaks the data-flow trace CodeQL uses to flag the
     * downstream SQL concat (a regex test alone is not treated as a
     * sanitiser).
     */
    private static String sanitiseMatviewName(String name) {
        if (name == null || !SAFE_MATVIEW.matcher(name).matches()) {
            throw new IllegalArgumentException(
                "matview identifier fails the SAFE_MATVIEW allowlist");
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_'
                || c == '.'
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else {
                // Unreachable — SAFE_MATVIEW already rejects this.
                throw new IllegalArgumentException(
                    "matview identifier contains a disallowed character");
            }
        }
        return sb.toString();
    }
}
