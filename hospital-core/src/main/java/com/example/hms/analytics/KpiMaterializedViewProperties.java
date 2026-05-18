package com.example.hms.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the KPI dashboard materialized-view tier
 * (roadmap row 32 follow-on).
 *
 * <p>The row-32 foundation pass kept all three KPI queries on the
 * on-the-fly native-SQL path. This follow-on adds optional
 * pre-aggregated matviews (V105) that {@code KpiDashboardServiceImpl}
 * prefers when both this flag is on AND the matview exists in the
 * connected database. The matview path is PostgreSQL-only —
 * H2-backed test deployments evaluate the flag as false (the V105
 * preCondition marks the changeset RAN without applying it on H2,
 * so the matview never exists) and continue down the existing
 * on-the-fly path bit-for-bit unchanged.
 *
 * <p>Refresh: when {@link #isRefreshSchedulerEnabled()} is also on,
 * {@link KpiMaterializedViewRefreshScheduler} runs
 * {@code REFRESH MATERIALIZED VIEW CONCURRENTLY} on a fixed
 * {@link #getRefreshIntervalMs()} interval (default 5 minutes).
 * Activation should ship the refresh-scheduler flag together with
 * the matview flag — without the refresher, the matview tier
 * staleness grows monotonically.
 */
@ConfigurationProperties(prefix = "app.analytics.kpi.materialized-views")
public class KpiMaterializedViewProperties {

    /**
     * Master switch. When {@code false} (the default) the service
     * uses on-the-fly native SQL exactly as the row-32 foundation
     * pass shipped — the foundation behaviour is bit-for-bit
     * unchanged.
     */
    private boolean enabled = false;

    /**
     * When the matview tier is enabled, controls whether the in-app
     * scheduler runs {@code REFRESH MATERIALIZED VIEW CONCURRENTLY}.
     * Operators may prefer to refresh from an external cron / DBA
     * job — in that case set this to {@code false} and own the
     * refresh cadence externally. Off by default to avoid silently
     * starting a refresher on environments that flip the read
     * flag for testing.
     */
    private boolean refreshSchedulerEnabled = false;

    /**
     * Refresh cadence in milliseconds. Default 5 minutes. Lower
     * values raise CPU on Postgres; higher values raise staleness
     * the dashboard surfaces.
     */
    private long refreshIntervalMs = 300_000L;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isRefreshSchedulerEnabled() { return refreshSchedulerEnabled; }
    public void setRefreshSchedulerEnabled(boolean v) { this.refreshSchedulerEnabled = v; }

    public long getRefreshIntervalMs() { return refreshIntervalMs; }
    public void setRefreshIntervalMs(long v) { this.refreshIntervalMs = v; }
}
