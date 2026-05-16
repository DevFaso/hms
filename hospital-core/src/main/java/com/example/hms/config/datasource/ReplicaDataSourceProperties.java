package com.example.hms.config.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;

/**
 * Configuration for an optional read-only PostgreSQL replica
 * (roadmap row 35, {@code v2.0 / Performance / Read replicas + Hikari tuning}).
 *
 * <p>Disabled by default. When {@link #isEnabled()} returns {@code true} the
 * {@code ReadReplicaDataSourceConfiguration} builds a secondary Hikari pool
 * pointed at the replica and the primary {@code DataSource} bean is
 * replaced with a {@link ReadWriteRoutingDataSource} that picks the
 * write or replica pool based on Spring's
 * {@code TransactionSynchronizationManager.isCurrentTransactionReadOnly()}
 * flag. Existing {@code @Transactional(readOnly = true)} methods (already
 * present on hot read paths in {@code PatientService} / {@code MeController} /
 * etc.) start routing to the replica without any source change.
 *
 * <p>Activation requires three env vars to be set together:
 * <pre>
 *   APP_DATASOURCE_REPLICA_ENABLED=true
 *   APP_DATASOURCE_REPLICA_URL=jdbc:postgresql://replica.host:5432/hospital_db
 *   APP_DATASOURCE_REPLICA_USERNAME=hms_app_ro
 *   APP_DATASOURCE_REPLICA_PASSWORD=…
 * </pre>
 * If any of the URL / username / password is blank while
 * {@code enabled=true}, the configuration fails fast at boot rather than
 * silently degrading back to the write pool — operators should know they
 * intended a replica.
 */
@ConfigurationProperties(prefix = "app.datasource.replica")
public class ReplicaDataSourceProperties {

    /**
     * Master switch. When {@code false} (the default) no replica pool is
     * created and the primary {@code DataSource} bean is the existing
     * write Hikari pool — bit-for-bit unchanged from the pre-row-35
     * baseline.
     */
    private boolean enabled = false;

    /**
     * JDBC URL for the read replica. PostgreSQL streaming replicas can
     * be wired as a hot-standby; logical replicas work too. Must include
     * the {@code jdbc:postgresql://} scheme — unlike the primary
     * {@code spring.datasource.url}, no {@code postgresql://} → jdbc
     * rewriting is performed here.
     */
    private String url;

    private String username;
    private String password;

    /**
     * JDBC driver class. Defaults to PostgreSQL; documented so a future
     * non-Postgres standby setup can override without code changes.
     */
    private String driverClassName = "org.postgresql.Driver";

    @NestedConfigurationProperty
    private final HikariPoolTuning hikari = new HikariPoolTuning();

    /**
     * Hikari pool tuning for the replica. Mirrors the primary's tuning
     * knobs but kept separate so the replica can run a smaller pool —
     * read replicas are typically lower-concurrency than the primary
     * (dashboards + FHIR reads + light reporting), so a smaller maximum
     * pool size avoids holding idle connections that the replica's own
     * memory tuning is unlikely to forgive.
     */
    public static class HikariPoolTuning {
        /** Maximum number of connections in the replica pool. */
        private int maximumPoolSize = 10;
        /** Minimum idle connections kept warm. */
        private int minimumIdle = 2;
        /** ms — max age of any connection in the pool before recycle. */
        private long maxLifetimeMs = 1_800_000L;       // 30 min
        /** ms — idle connection eviction threshold. */
        private long idleTimeoutMs = 600_000L;         // 10 min
        /** ms — get-connection timeout from the pool. */
        private long connectionTimeoutMs = 5_000L;     // 5 s — faster fail than primary; replicas are advisory
        /** ms — leak detection threshold (0 disables). */
        private long leakDetectionThresholdMs = 0L;
        /** Pool name surfaced in Hikari MXBeans + log output. */
        private String poolName = "hms-replica-pool";
        /** SQL run on connection check-out / idle test. Postgres-specific. */
        private String connectionTestQuery;

        public int getMaximumPoolSize() { return maximumPoolSize; }
        public void setMaximumPoolSize(int v) { this.maximumPoolSize = v; }
        public int getMinimumIdle() { return minimumIdle; }
        public void setMinimumIdle(int v) { this.minimumIdle = v; }
        public long getMaxLifetimeMs() { return maxLifetimeMs; }
        public void setMaxLifetimeMs(long v) { this.maxLifetimeMs = v; }
        public long getIdleTimeoutMs() { return idleTimeoutMs; }
        public void setIdleTimeoutMs(long v) { this.idleTimeoutMs = v; }
        public long getConnectionTimeoutMs() { return connectionTimeoutMs; }
        public void setConnectionTimeoutMs(long v) { this.connectionTimeoutMs = v; }
        public long getLeakDetectionThresholdMs() { return leakDetectionThresholdMs; }
        public void setLeakDetectionThresholdMs(long v) { this.leakDetectionThresholdMs = v; }
        public String getPoolName() { return poolName; }
        public void setPoolName(String v) { this.poolName = v; }
        public String getConnectionTestQuery() { return connectionTestQuery; }
        public void setConnectionTestQuery(String v) { this.connectionTestQuery = v; }
    }

    /**
     * Builds the replica {@link HikariDataSource} from the configured
     * URL + credentials + Hikari tuning. The pool is marked
     * {@code readOnly=true} so the JDBC driver short-circuits any
     * accidental write attempt with a clearer error message than the
     * server-side {@code ERROR:  cannot execute INSERT in a read-only transaction}.
     *
     * <p>Throws {@link IllegalStateException} if {@code enabled=true} but
     * any of the URL / username / password is blank — fail fast rather
     * than silently degrading to no-op.
     */
    public HikariDataSource buildReplicaDataSource() {
        validateForActivation();
        HikariDataSource ds = (HikariDataSource) DataSourceBuilder.create()
            .type(HikariDataSource.class)
            .driverClassName(driverClassName)
            .url(url)
            .username(username)
            .password(password)
            .build();
        ds.setReadOnly(true);
        ds.setMaximumPoolSize(hikari.maximumPoolSize);
        ds.setMinimumIdle(hikari.minimumIdle);
        ds.setMaxLifetime(hikari.maxLifetimeMs);
        ds.setIdleTimeout(hikari.idleTimeoutMs);
        ds.setConnectionTimeout(hikari.connectionTimeoutMs);
        if (hikari.leakDetectionThresholdMs > 0) {
            ds.setLeakDetectionThreshold(hikari.leakDetectionThresholdMs);
        }
        if (hikari.poolName != null && !hikari.poolName.isBlank()) {
            ds.setPoolName(hikari.poolName);
        }
        if (hikari.connectionTestQuery != null && !hikari.connectionTestQuery.isBlank()) {
            ds.setConnectionTestQuery(hikari.connectionTestQuery);
        }
        return ds;
    }

    void validateForActivation() {
        if (!enabled) return;
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                "app.datasource.replica.enabled=true but app.datasource.replica.url is blank");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalStateException(
                "app.datasource.replica.enabled=true but app.datasource.replica.username is blank");
        }
        if (password == null) {
            throw new IllegalStateException(
                "app.datasource.replica.enabled=true but app.datasource.replica.password is null"
                    + " (empty string is allowed for trust auth, null is not)");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getDriverClassName() { return driverClassName; }
    public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }
    public HikariPoolTuning getHikari() { return hikari; }
}
