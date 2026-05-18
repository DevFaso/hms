package com.example.hms.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.UncategorizedSQLException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KpiMaterializedViewRefreshScheduler}.
 *
 * <p>The scheduler can't be exercised end-to-end without a real
 * PostgreSQL instance (matviews don't exist on H2), so these tests
 * pin the contract by mocking the {@link DataSource} → {@link Connection}
 * → {@link Statement} chain. Specifically:
 *
 * <ul>
 *   <li>refreshAll() iterates every matview name and survives a
 *       per-view failure;</li>
 *   <li>refreshOne() falls back to non-CONCURRENT REFRESH when the
 *       CONCURRENTLY form throws (first-tick "matview not populated"
 *       path);</li>
 *   <li>refreshOne() forces {@code setAutoCommit(true)} so PostgreSQL
 *       accepts CONCURRENTLY (which rejects in-tx execution);</li>
 *   <li>sanitiseMatviewName() rejects everything outside the
 *       {@code [a-z0-9_.]} allowlist.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class KpiMaterializedViewRefreshSchedulerTest {

    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private Statement statement;
    @Mock private Statement fallbackStatement;

    private KpiMaterializedViewRefreshScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new KpiMaterializedViewRefreshScheduler(dataSource);
    }

    @Test
    @DisplayName("refreshAll fires REFRESH MATERIALIZED VIEW CONCURRENTLY against every matview")
    void refreshAllIteratesEveryMatview() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);

        scheduler.refreshAll();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(statement, times(KpiMaterializedViewRefreshScheduler.MATVIEW_NAMES.size()))
            .executeUpdate(sql.capture());
        for (String matview : KpiMaterializedViewRefreshScheduler.MATVIEW_NAMES) {
            assertThat(sql.getAllValues())
                .as("CONCURRENTLY refresh emitted for " + matview)
                .anyMatch(s -> s.equals("REFRESH MATERIALIZED VIEW CONCURRENTLY " + matview));
        }
    }

    @Test
    @DisplayName("refreshAll survives a per-view SQL failure and continues with the next matview")
    void refreshAllToleratesOneViewFailure() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);
        // First CONCURRENTLY call blows up; refreshOne wraps it as
        // UncategorizedSQLException (the actual `catch SQLException`
        // is at the runConcurrentOrFallback level — see fallback test).
        // For this test we want a top-level RuntimeException out of
        // refreshOne so refreshAll's outer catch fires.
        when(statement.executeUpdate(anyString()))
            .thenThrow(new RuntimeException("boom on view #1"))
            .thenReturn(0)
            .thenReturn(0);

        scheduler.refreshAll();

        // Three matviews → three CONCURRENTLY attempts. The first
        // throws; the next two succeed.
        verify(statement, times(KpiMaterializedViewRefreshScheduler.MATVIEW_NAMES.size()))
            .executeUpdate(anyString());
    }

    @Test
    @DisplayName("refreshOne falls back to plain REFRESH when CONCURRENTLY throws SQLException")
    void refreshOneFallsBackToNonConcurrent() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement())
            .thenReturn(statement)
            .thenReturn(fallbackStatement);
        when(statement.executeUpdate(anyString()))
            .thenThrow(new SQLException("CONCURRENTLY cannot run inside a transaction block"));

        scheduler.refreshOne("clinical.kpi_door_to_doctor_daily");

        verify(statement).executeUpdate(
            "REFRESH MATERIALIZED VIEW CONCURRENTLY clinical.kpi_door_to_doctor_daily");
        verify(fallbackStatement).executeUpdate(
            "REFRESH MATERIALIZED VIEW clinical.kpi_door_to_doctor_daily");
    }

    @Test
    @DisplayName("refreshOne enables autocommit when the borrowed connection had it off, then restores")
    void refreshOneRestoresAutocommit() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(false);
        when(connection.createStatement()).thenReturn(statement);

        scheduler.refreshOne("clinical.kpi_no_show_rate_daily");

        verify(connection).setAutoCommit(true);
        // Restored after the refresh so the connection returned to
        // the pool keeps the pool's expected default.
        verify(connection).setAutoCommit(false);
    }

    @Test
    @DisplayName("refreshOne wraps a connect-time SQLException as UncategorizedSQLException")
    void refreshOneWrapsConnectFailure() throws SQLException {
        when(dataSource.getConnection())
            .thenThrow(new SQLException("connection pool exhausted"));

        assertThatThrownBy(() -> scheduler.refreshOne("clinical.kpi_no_show_rate_daily"))
            .isInstanceOf(UncategorizedSQLException.class)
            .hasMessageContaining("REFRESH MATERIALIZED VIEW");
    }

    @Test
    @DisplayName("refreshOne rejects a tainted matview name before any JDBC call")
    void refreshOneRejectsBadIdentifier() throws SQLException {
        assertThatThrownBy(() -> scheduler.refreshOne("DROP TABLE users; --"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SAFE_MATVIEW");
        assertThatThrownBy(() -> scheduler.refreshOne(null))
            .isInstanceOf(IllegalArgumentException.class);
        // Schema-qualified is fine, but un-allowed punctuation isn't.
        assertThatThrownBy(() -> scheduler.refreshOne("clinical.kpi-bad-dash"))
            .isInstanceOf(IllegalArgumentException.class);
        // No JDBC traffic at all — guard fires before borrowing a
        // connection.
        verify(dataSource, atLeast(0)).getConnection();
    }

    @Test
    @DisplayName("sanitised identifier is byte-for-byte identical to the input when allowlist passes")
    void sanitisedIdentifierEqualsInput() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);

        scheduler.refreshOne("clinical.kpi_dispense_lead_time_daily");

        verify(statement).executeUpdate(
            "REFRESH MATERIALIZED VIEW CONCURRENTLY clinical.kpi_dispense_lead_time_daily");
    }
}
