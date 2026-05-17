package com.example.hms.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.config.datasource.ReadWriteRoutingDataSource;
import com.example.hms.config.datasource.ReplicaDataSourceProperties;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class ReadReplicaHealthIndicatorTest {

    @Mock private DataSource writeOnlyPrimary;
    @Mock private DataSource replicaDataSource;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private Connection connection;
    @Mock private DatabaseMetaData metaData;
    @Mock private Statement statement;
    @Mock private ResultSet resultSet;

    private ReplicaDataSourceProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ReplicaDataSourceProperties();
        // Stub transaction manager to execute the callback inline so the
        // probe runs synchronously in the test thread.
        TransactionStatus txStatus = new SimpleTransactionStatus();
        lenient().when(transactionManager.getTransaction(any())).thenReturn(txStatus);
    }

    @Test
    @DisplayName("UP with routing=disabled when flag is off and primary is the bare write pool")
    void disabledFlagReportsRoutingDisabled() {
        ReadReplicaHealthIndicator indicator = new ReadReplicaHealthIndicator(
            properties, writeOnlyPrimary, null, transactionManager);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
            .containsEntry(ReadReplicaHealthIndicator.DETAIL_ROUTING, "disabled");
        // No SQL probe runs when the wrapper is absent.
        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    @DisplayName("DOWN when flag is enabled but the routing wrapper is missing (wiring drift)")
    void enabledFlagWithoutRoutingWrapperReportsDown() {
        properties.setEnabled(true);
        ReadReplicaHealthIndicator indicator = new ReadReplicaHealthIndicator(
            properties, writeOnlyPrimary, replicaDataSource, transactionManager);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
            .containsEntry(ReadReplicaHealthIndicator.DETAIL_ROUTING, "missing-wrapper");
        assertThat(health.getDetails().get(ReadReplicaHealthIndicator.DETAIL_ERROR))
            .asString()
            .contains("ReadWriteRoutingDataSource");
    }

    @Test
    @DisplayName("UP with routedTo=READ when the probe lands on the configured replica URL")
    void probeRoutesToReadWhenUrlMatches() throws SQLException {
        String replicaUrl = "jdbc:postgresql://replica.local:5432/hospital_db";
        properties.setEnabled(true);
        properties.setUrl(replicaUrl);
        properties.setUsername("ro");
        properties.setPassword("pw");

        ReadWriteRoutingDataSource routing = new ReadWriteRoutingDataSource(
            writeOnlyPrimary, replicaDataSource);
        wireRouteProbe(replicaUrl, /* inRecovery */ true, /* lagSeconds */ 0.42);

        ReadReplicaHealthIndicator indicator = new ReadReplicaHealthIndicator(
            properties, routing, replicaDataSource, transactionManager);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
            .containsEntry(ReadReplicaHealthIndicator.DETAIL_ROUTING, "enabled")
            .containsEntry(ReadReplicaHealthIndicator.DETAIL_ROUTED_TO, "READ")
            .containsEntry(ReadReplicaHealthIndicator.DETAIL_REPLICA_REACHABLE, true)
            .containsEntry(ReadReplicaHealthIndicator.DETAIL_REPLICA_IN_RECOVERY, true)
            .containsEntry(ReadReplicaHealthIndicator.DETAIL_REPLICA_LAG_SECONDS, 0.42);
    }

    @Test
    @DisplayName("UP with routedTo=WRITE when lenient fallback degrades the probe to the primary")
    void probeReportsRoutedToWriteWhenUrlMismatches() throws SQLException {
        properties.setEnabled(true);
        // Operator declared a replica URL, but the probe will land on
        // the WRITE pool because lenient fallback engaged (e.g. the
        // replica HikariDataSource bean failed to construct cleanly).
        properties.setUrl("jdbc:postgresql://replica.local:5432/hospital_db");
        properties.setUsername("ro");
        properties.setPassword("pw");

        ReadWriteRoutingDataSource routing = new ReadWriteRoutingDataSource(
            writeOnlyPrimary, /* readDataSource */ null);
        wireRouteProbe(
            "jdbc:postgresql://write.local:5432/hospital_db",
            /* inRecovery */ false, /* lagSeconds */ 0.0);

        ReadReplicaHealthIndicator indicator = new ReadReplicaHealthIndicator(
            properties, routing, /* replica */ null, transactionManager);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
            .containsEntry(ReadReplicaHealthIndicator.DETAIL_ROUTED_TO, "WRITE");
        // No replica freshness details when the replica DataSource isn't injected.
        assertThat(health.getDetails())
            .doesNotContainKey(ReadReplicaHealthIndicator.DETAIL_REPLICA_LAG_SECONDS);
    }

    @Test
    @DisplayName("DOWN when the SELECT 1 probe throws — surfaces the cause in the details")
    void probeFailureReportsDown() throws SQLException {
        properties.setEnabled(true);
        properties.setUrl("jdbc:postgresql://replica.local:5432/hospital_db");
        properties.setUsername("ro");
        properties.setPassword("pw");

        ReadWriteRoutingDataSource routing = new ReadWriteRoutingDataSource(
            writeOnlyPrimary, replicaDataSource);
        // primary.getConnection() throws — the routing wrapper would
        // normally hand back a wrapped connection from the WRITE pool,
        // but the underlying write pool itself rejected the request.
        when(writeOnlyPrimary.getConnection())
            .thenThrow(new SQLException("write pool exhausted"));

        ReadReplicaHealthIndicator indicator = new ReadReplicaHealthIndicator(
            properties, routing, replicaDataSource, transactionManager);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get(ReadReplicaHealthIndicator.DETAIL_ERROR))
            .asString()
            .contains("read-route probe failed");
    }

    /**
     * Wires the mock primary + replica + connection + result-set so the
     * routing wrapper's {@code SELECT 1} probe yields a deterministic
     * URL and the replica freshness query yields the supplied lag. The
     * caller constructs the {@link ReadWriteRoutingDataSource}
     * separately and passes it to the indicator under test — this
     * helper only stubs the underlying mock JDBC graph; the routing
     * wrapper itself isn't an input.
     */
    private void wireRouteProbe(
        String probeReportsUrl,
        boolean inRecovery,
        double lagSeconds
    ) throws SQLException {
        // Every stub is lenient — some tests don't traverse every
        // mock (the routedTo=WRITE test never reaches the replica
        // connection, the freshness query never runs when the routing
        // wrapper degrades to WRITE, etc.). Strict stubs would flag
        // these as unused; lenient() makes the helper reusable across
        // the four scenarios without per-test branching.
        lenient().when(replicaDataSource.getConnection()).thenReturn(connection);
        lenient().when(writeOnlyPrimary.getConnection()).thenReturn(connection);
        lenient().when(connection.createStatement()).thenReturn(statement);
        lenient().when(statement.executeQuery(any())).thenReturn(resultSet);
        lenient().when(connection.getMetaData()).thenReturn(metaData);
        lenient().when(metaData.getURL()).thenReturn(probeReportsUrl);
        lenient().when(resultSet.next()).thenReturn(true);
        lenient().when(resultSet.getBoolean("in_recovery")).thenReturn(inRecovery);
        lenient().when(resultSet.getDouble("lag_seconds")).thenReturn(lagSeconds);
        lenient().when(resultSet.getTimestamp("last_replay"))
            .thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 5, 17, 16, 0)));
    }
}
