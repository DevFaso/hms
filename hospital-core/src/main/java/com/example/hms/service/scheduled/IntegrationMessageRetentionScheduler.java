package com.example.hms.service.scheduled;

import com.example.hms.service.integration.message.IntegrationMessageRetentionPolicy;
import com.example.hms.service.integration.message.IntegrationMessageRetentionService;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Nightly erasure of integration message content past its retention window
 * (user decision 2026-10-04): the {@code integration_message_event} rows stay
 * forever as audit evidence, the stored message bodies do not.
 *
 * <p>Properties ({@code hms.integration.retention.*}; {@code enabled},
 * {@code payload-days} and {@code unresolved-max-days} are read and validated
 * by {@link IntegrationMessageRetentionPolicy}, which the operator page
 * shares):
 * <ul>
 *   <li>{@code enabled} (default {@code true}) - the whole sweep.</li>
 *   <li>{@code cron} (default 03:30 daily).</li>
 *   <li>{@code payload-days} (default 180) - age, or for a resolved dead
 *   letter time since resolution, after which the content is erased. Below 1
 *   the sweep refuses to run rather than erase everything.</li>
 *   <li>{@code unresolved-max-days} (default 365) - the absolute ceiling for
 *   any failed message, resolved or not, superseded or not: past it the
 *   content goes even if the row is still replayable, so nothing is kept for
 *   ever. Below {@code payload-days} the sweep refuses to run - a ceiling
 *   shorter than the window would erase failed messages before ordinary
 *   traffic.</li>
 *   <li>{@code batch-size} (default 500) - rows per transaction.</li>
 *   <li>{@code max-batches} (default 200) - per run, so one run stays well
 *   inside its lock; a larger backlog finishes on the following nights.</li>
 * </ul>
 *
 * <p>Multi-instance safe twice over: ShedLock lets one instance run it, and
 * the purge UPDATE re-checks eligibility, so a row purged elsewhere is simply
 * not updated again. Logs counts only - never an id, a sender or a body.
 */
@Component
@Slf4j
public class IntegrationMessageRetentionScheduler {

    static final int MAX_BATCH_SIZE = 5_000;

    private final IntegrationMessageRetentionService retentionService;
    private final IntegrationMessageRetentionPolicy policy;
    private final Clock clock;
    private final int batchSize;
    private final int maxBatches;

    public IntegrationMessageRetentionScheduler(
        IntegrationMessageRetentionService retentionService,
        IntegrationMessageRetentionPolicy policy,
        Clock clock,
        @Value("${hms.integration.retention.batch-size:500}") int batchSize,
        @Value("${hms.integration.retention.max-batches:200}") int maxBatches
    ) {
        this.retentionService = retentionService;
        this.policy = policy;
        this.clock = clock;
        this.batchSize = Math.clamp(batchSize, 1, MAX_BATCH_SIZE);
        this.maxBatches = Math.max(1, maxBatches);
    }

    /**
     * One sweep. Boxed return so ShedLock can answer null for a run another
     * instance holds; the value is the number of rows purged (0 when disabled
     * or misconfigured).
     */
    @SchedulerLock(name = "IntegrationMessageRetentionScheduler.purgeExpiredPayloads",
        lockAtMostFor = "PT1H", lockAtLeastFor = "PT5S")
    @Scheduled(cron = "${hms.integration.retention.cron:0 30 3 * * *}")
    public Integer purgeExpiredPayloads() {
        if (!policy.isEnabled()) {
            log.debug("[INTEGRATION-RETENTION] Skipping sweep - disabled in this environment");
            return 0;
        }
        String problem = policy.configurationProblem();
        if (problem != null) {
            log.error("[INTEGRATION-RETENTION] Refusing to run - {}", problem);
            return 0;
        }
        int payloadDays = policy.payloadDays();
        int unresolvedMaxDays = policy.unresolvedMaxDays();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime cutoff = now.minusDays(payloadDays);
        LocalDateTime unresolvedCutoff = now.minusDays(unresolvedMaxDays);
        int total = 0;
        try {
            for (int batch = 0; batch < maxBatches; batch++) {
                int purged = retentionService.purgeBatch(cutoff, unresolvedCutoff, now, batchSize);
                total += purged;
                if (purged < batchSize) {
                    break;
                }
            }
        } catch (RuntimeException ex) {
            // Never propagate out of a @Scheduled method. Batches already
            // committed stay committed; the rest is picked up next run.
            log.error("[INTEGRATION-RETENTION] Sweep stopped after {} row(s): {}",
                total, ex.getClass().getSimpleName(), ex);
        }
        if (total > 0) {
            log.info("[INTEGRATION-RETENTION] Purged message content from {} row(s) "
                + "(window {} days, unresolved ceiling {} days)", total, payloadDays, unresolvedMaxDays);
        }
        return total;
    }
}
