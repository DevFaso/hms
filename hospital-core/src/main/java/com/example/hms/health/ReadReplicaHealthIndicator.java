package com.example.hms.health;

import com.example.hms.config.datasource.ReadWriteRoutingDataSource;
import com.example.hms.config.datasource.ReadWriteRoutingDataSource.Route;
import com.example.hms.config.datasource.ReplicaDataSourceProperties;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;

/**
 * Surfaces read-replica routing posture under
 * {@code /api/actuator/health/readReplica} (roadmap row 35 follow-on).
 *
 * <p>Three signals consolidated into a single health check:
 *
 * <ul>
 *   <li><b>Routing wiring</b> — whether the application bean graph
 *       composed a {@link ReadWriteRoutingDataSource} on top of the
 *       write pool. When {@code app.datasource.replica.enabled=false}
 *       no routing wrapper exists and the indicator reports
 *       {@code UP} with {@code routing=disabled} — this is the
 *       expected bit-for-bit-unchanged baseline.</li>
 *   <li><b>READ-route probe</b> — runs a {@code SELECT 1} inside a
 *       {@code @Transactional(readOnly = true)} block so the routing
 *       wrapper's {@link Route#READ} lookup key fires. If the replica
 *       pool is wired the probe lands on it; otherwise the wrapper
 *       falls back to the write pool (per
 *       {@code ReadWriteRoutingDataSource#setLenientFallback(true)})
 *       and the indicator surfaces {@code routedTo=WRITE} so operators
 *       see the misconfiguration even though the probe itself succeeds.</li>
 *   <li><b>Replication freshness</b> — when wired, also queries
 *       {@code pg_is_in_recovery()} and
 *       {@code pg_last_xact_replay_timestamp()} on the replica pool
 *       directly so the lag is visible in the health payload without
 *       needing the operator to keep a psql window open. The lag is
 *       advisory: this indicator does NOT fail when lag exceeds a
 *       threshold (alerting belongs in Prometheus, not in
 *       Spring Actuator).</li>
 * </ul>
 *
 * <p>Status:
 * <ul>
 *   <li>{@code UP} — wiring is consistent with the flag.</li>
 *   <li>{@code DOWN} — flag says enabled, but the routing wrapper is
 *       absent or the replica pool fails its {@code SELECT 1}.</li>
 * </ul>
 *
 * <p>Operators can opt out per-environment with
 * {@code management.health.readReplica.enabled=false} — the
 * {@link ConditionalOnEnabledHealthIndicator} guard.
 */
@Component("readReplica")
@ConditionalOnEnabledHealthIndicator("readReplica")
public class ReadReplicaHealthIndicator implements HealthIndicator {

    static final String DETAIL_ROUTING = "routing";
    static final String DETAIL_ROUTED_TO = "routedTo";
    static final String DETAIL_REPLICA_REACHABLE = "replicaReachable";
    static final String DETAIL_REPLICA_IN_RECOVERY = "replicaInRecovery";
    static final String DETAIL_REPLICA_LAST_REPLAY_TIMESTAMP =
        "replicaLastReplayTimestamp";
    static final String DETAIL_REPLICA_LAG_SECONDS = "replicaLagSeconds";
    static final String DETAIL_ERROR = "error";

    private final ReplicaDataSourceProperties properties;
    private final DataSource primaryDataSource;
    private final Optional<DataSource> replicaDataSource;
    private final TransactionTemplate readOnlyTemplate;

    public ReadReplicaHealthIndicator(
        ReplicaDataSourceProperties properties,
        DataSource primaryDataSource,
        @Qualifier("replicaDataSource")
        @Autowired(required = false)
        DataSource replicaDataSource,
        PlatformTransactionManager transactionManager
    ) {
        this.properties = properties;
        this.primaryDataSource = primaryDataSource;
        this.replicaDataSource = Optional.ofNullable(replicaDataSource);
        // REQUIRES_NEW so the probe is independent of any caller's
        // transaction context (the indicator can be invoked from inside
        // an in-flight write transaction by the actuator framework if
        // the operator hits the endpoint from a side channel).
        // Read-only flag triggers the routing wrapper.
        this.readOnlyTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTemplate.setReadOnly(true);
        this.readOnlyTemplate.setPropagationBehavior(
            TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Health health() {
        boolean routingWired = primaryDataSource instanceof ReadWriteRoutingDataSource;
        boolean flagEnabled = properties.isEnabled();

        // Wiring inconsistency is the only hard DOWN condition for this
        // indicator. The flag is the operator's stated intent; the
        // routing wrapper is the actual wiring outcome. If they
        // disagree the deployment is misconfigured and the operator
        // should know immediately — a 5xx on /actuator/health/readReplica
        // is the cheapest paging signal for that.
        if (flagEnabled && !routingWired) {
            return Health.down()
                .withDetail(DETAIL_ROUTING, "missing-wrapper")
                .withDetail(DETAIL_ERROR,
                    "app.datasource.replica.enabled=true but primary DataSource is not"
                        + " a ReadWriteRoutingDataSource — check ReadReplicaDataSourceConfiguration"
                        + " activation and the optional injection in DataSourceConfig#dataSource")
                .build();
        }

        Health.Builder builder = Health.up()
            .withDetail(DETAIL_ROUTING, routingWired ? "enabled" : "disabled");

        // READ-route probe is only meaningful when the wrapper is in
        // place. When disabled, /actuator/health/readReplica still
        // returns UP — operators reading the payload see
        // routing=disabled and stop digging.
        if (!routingWired) {
            return builder.build();
        }

        Route routedTo;
        try {
            routedTo = probeReadRoute();
        } catch (RuntimeException ex) {
            return Health.down()
                .withDetail(DETAIL_ROUTING, "enabled")
                .withDetail(DETAIL_ERROR, "read-route probe failed: " + ex.toString())
                .build();
        }
        builder.withDetail(DETAIL_ROUTED_TO, routedTo.name());

        if (routedTo != Route.READ) {
            // Wrapper is in place but lenient fallback engaged — the
            // replica pool isn't wired (or the bean failed) so reads
            // are landing on the write primary. Report UP because the
            // application is serving traffic, but mark routedTo=WRITE
            // so the operator dashboard can chart the misroute count.
            return builder.build();
        }

        addReplicaFreshnessDetails(builder);
        return builder.build();
    }

    /**
     * Runs {@code SELECT 1} inside a {@code @Transactional(readOnly = true)}
     * block — Spring's transaction-synchronization manager flips the
     * read-only flag BEFORE the routing wrapper acquires a connection,
     * so the wrapper sees {@link Route#READ}. The actual DataSource that
     * served the connection is what we report; the wrapper's
     * {@code lenientFallback=true} means a missing READ target degrades
     * to WRITE silently — that's the misroute the indicator surfaces.
     */
    private Route probeReadRoute() {
        return readOnlyTemplate.execute(status -> {
            try (Connection conn = primaryDataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1")) {
                rs.next();
                String url = conn.getMetaData().getURL();
                // The replica properties' URL is the operator's
                // declared identity for the replica pool. A match on
                // the connection's JDBC URL is the most reliable way
                // to confirm the lenient-fallback didn't engage —
                // querying pool name via reflection on the Hikari
                // wrapper is brittle across version bumps.
                if (properties.getUrl() != null
                    && properties.getUrl().equalsIgnoreCase(url)) {
                    return Route.READ;
                }
                return Route.WRITE;
            } catch (java.sql.SQLException ex) {
                throw new IllegalStateException(
                    "read-route SELECT 1 failed: " + ex.getMessage(), ex);
            }
        });
    }

    /**
     * Pulls {@code pg_is_in_recovery()} +
     * {@code pg_last_xact_replay_timestamp()} +
     * {@code now() - pg_last_xact_replay_timestamp()} (seconds) from
     * the replica pool DIRECTLY (not through the routing wrapper) so
     * the result reflects the replica's own state even when the
     * routing wrapper is degrading to WRITE.
     *
     * <p>Wrapped in try/catch — replication freshness is advisory; a
     * Postgres version that doesn't expose these functions (or a
     * non-Postgres standby) should not blow up the health endpoint.
     */
    private void addReplicaFreshnessDetails(Health.Builder builder) {
        replicaDataSource.ifPresent(ds -> {
            try (Connection conn = ds.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT pg_is_in_recovery() AS in_recovery,"
                         + " pg_last_xact_replay_timestamp() AS last_replay,"
                         + " EXTRACT(EPOCH FROM (now() - pg_last_xact_replay_timestamp()))"
                         + " AS lag_seconds")) {
                if (rs.next()) {
                    builder
                        .withDetail(DETAIL_REPLICA_REACHABLE, true)
                        .withDetail(DETAIL_REPLICA_IN_RECOVERY, rs.getBoolean("in_recovery"))
                        .withDetail(DETAIL_REPLICA_LAST_REPLAY_TIMESTAMP,
                            String.valueOf(rs.getTimestamp("last_replay")))
                        .withDetail(DETAIL_REPLICA_LAG_SECONDS, rs.getDouble("lag_seconds"));
                }
            } catch (java.sql.SQLException ex) {
                builder
                    .withDetail(DETAIL_REPLICA_REACHABLE, false)
                    .withDetail(DETAIL_ERROR, "replica freshness query failed: " + ex.getMessage());
            }
        });
    }
}
