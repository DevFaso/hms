package com.example.hms.analytics;

import com.example.hms.analytics.KpiMaterializedViewRefreshScheduler.MatviewName;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KpiMaterializedViewRefreshScheduler}.
 *
 * <p>The scheduler can't be exercised end-to-end without a real
 * PostgreSQL instance (matviews don't exist on H2), so these tests
 * pin the contract by mocking the {@link DataSource} → {@link Connection}
 * → {@link Statement} chain.
 *
 * <p>SQL is dispatched via per-matview literal-SQL branches (no
 * concatenation, no Sonar S2077 surface), so tests assert on the
 * exact literal strings the production path emits — that way a
 * future refactor that accidentally drifts the SQL fails loudly here.
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
        verify(statement, times(KpiMaterializedViewRefreshScheduler.ALL_MATVIEWS.size()))
            .executeUpdate(sql.capture());
        assertThat(sql.getAllValues())
            .containsExactlyInAnyOrder(
                "REFRESH MATERIALIZED VIEW CONCURRENTLY clinical.kpi_door_to_doctor_daily",
                "REFRESH MATERIALIZED VIEW CONCURRENTLY clinical.kpi_dispense_lead_time_daily",
                "REFRESH MATERIALIZED VIEW CONCURRENTLY clinical.kpi_no_show_rate_daily");
    }

    @Test
    @DisplayName("refreshAll survives a per-view SQL failure and continues with the next matview")
    void refreshAllToleratesOneViewFailure() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeUpdate(anyString()))
            .thenThrow(new RuntimeException("boom on view #1"))
            .thenReturn(0)
            .thenReturn(0);

        scheduler.refreshAll();

        verify(statement, times(KpiMaterializedViewRefreshScheduler.ALL_MATVIEWS.size()))
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

        scheduler.refreshOne(MatviewName.DOOR_TO_DOCTOR);

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

        scheduler.refreshOne(MatviewName.NO_SHOW_RATE);

        verify(connection).setAutoCommit(true);
        verify(connection).setAutoCommit(false);
    }

    @Test
    @DisplayName("refreshOne wraps a connect-time SQLException as UncategorizedSQLException")
    void refreshOneWrapsConnectFailure() throws SQLException {
        when(dataSource.getConnection())
            .thenThrow(new SQLException("connection pool exhausted"));

        assertThatThrownBy(() -> scheduler.refreshOne(MatviewName.NO_SHOW_RATE))
            .isInstanceOf(UncategorizedSQLException.class)
            .hasMessageContaining("REFRESH MATERIALIZED VIEW");
    }

    @Test
    @DisplayName("refreshOne rejects a null matview before any JDBC call")
    void refreshOneRejectsNull() {
        assertThatThrownBy(() -> scheduler.refreshOne(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("each MatviewName dispatches to its expected literal-SQL form")
    void perMatviewLiteralSql() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);

        scheduler.refreshOne(MatviewName.DOOR_TO_DOCTOR);
        scheduler.refreshOne(MatviewName.DISPENSE_LEAD_TIME);
        scheduler.refreshOne(MatviewName.NO_SHOW_RATE);

        verify(statement).executeUpdate(
            "REFRESH MATERIALIZED VIEW CONCURRENTLY clinical.kpi_door_to_doctor_daily");
        verify(statement).executeUpdate(
            "REFRESH MATERIALIZED VIEW CONCURRENTLY clinical.kpi_dispense_lead_time_daily");
        verify(statement).executeUpdate(
            "REFRESH MATERIALIZED VIEW CONCURRENTLY clinical.kpi_no_show_rate_daily");
    }
}
