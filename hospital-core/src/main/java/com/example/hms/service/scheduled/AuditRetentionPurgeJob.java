package com.example.hms.service.scheduled;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes audit rows past the retention period, through the one delete the audit tables accept:
 * {@code support.purge_expired_audit_rows(retention_years, batch_limit)} (V382). The audit tables are
 * append-only; the database function enforces the six-year floor, deletes in batches and records each run
 * that removed rows as an {@code AUDIT_RETENTION_PURGED} audit event.
 *
 * <p>Properties ({@code hms.audit-retention.*}):
 * <ul>
 *   <li>{@code enabled} (default {@code false}) - rows are kept indefinitely unless an environment turns the
 *   purge on.</li>
 *   <li>{@code years} (default 6) - how long a row is kept. Below six the application refuses to start.</li>
 *   <li>{@code cron} (default 04:15 daily).</li>
 *   <li>{@code batch-size} (default 1000) - rows per table per call; {@code max-batches} (default 100) calls
 *   per run, so one run stays well inside its lock and a backlog finishes on the following nights.</li>
 * </ul>
 *
 * <p>No application code deletes or updates audit rows; AuditTrailAppendOnlyGuardTest keeps it that way.
 */
@Component
@Slf4j
public class AuditRetentionPurgeJob {

    /** The floor the database function enforces too (HIPAA documentation retention). */
    static final int MINIMUM_RETENTION_YEARS = 6;
    static final int MAX_BATCH_SIZE = 10_000;
    /** Startup refusal code (an operator setting, never shown to a user; the detail is in the ERROR log). */
    static final String RETENTION_BELOW_MINIMUM = "audit.retention.years.below-minimum";

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final int retentionYears;
    private final int batchSize;
    private final int maxBatches;

    public AuditRetentionPurgeJob(
        JdbcTemplate jdbc,
        @Value("${hms.audit-retention.enabled:false}") boolean enabled,
        @Value("${hms.audit-retention.years:6}") int retentionYears,
        @Value("${hms.audit-retention.batch-size:1000}") int batchSize,
        @Value("${hms.audit-retention.max-batches:100}") int maxBatches
    ) {
        if (retentionYears < MINIMUM_RETENTION_YEARS) {
            log.error("[AUDIT-RETENTION] hms.audit-retention.years must be at least {} (got {}); refusing to start",
                MINIMUM_RETENTION_YEARS, retentionYears);
            throw new IllegalStateException(RETENTION_BELOW_MINIMUM);
        }
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.retentionYears = retentionYears;
        this.batchSize = Math.clamp(batchSize, 1, MAX_BATCH_SIZE);
        this.maxBatches = Math.max(1, maxBatches);
    }

    /**
     * One run. Boxed so ShedLock can answer null for a run another instance holds; the value is the number of
     * rows removed (0 when disabled).
     */
    @SchedulerLock(name = "AuditRetentionPurgeJob.purgeExpiredAuditRows", lockAtMostFor = "PT1H",
        lockAtLeastFor = "PT5S")
    @Scheduled(cron = "${hms.audit-retention.cron:0 15 4 * * *}")
    public Long purgeExpiredAuditRows() {
        if (!enabled) {
            log.debug("[AUDIT-RETENTION] Skipping run - disabled in this environment");
            return 0L;
        }
        long total = 0;
        int calls = 0;
        while (calls < maxBatches) {
            Long removed = jdbc.queryForObject("SELECT support.purge_expired_audit_rows(?, ?)", Long.class,
                retentionYears, batchSize);
            calls++;
            long n = removed == null ? 0 : removed;
            total += n;
            // Each table removes at most batchSize per call: a total below that means every table is done.
            if (n < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("[AUDIT-RETENTION] {} audit row(s) older than {} years removed in {} call(s)", total,
                retentionYears, calls);
        } else {
            log.debug("[AUDIT-RETENTION] Nothing older than {} years", retentionYears);
        }
        return total;
    }
}
