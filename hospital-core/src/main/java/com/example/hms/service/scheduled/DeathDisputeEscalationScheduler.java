package com.example.hms.service.scheduled;

import com.example.hms.service.DeathDisputeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * An open dispute of a death is reviewed, or it comes back: an item that reached nobody is filed again, and one left
 * unreviewed is escalated. It never ends a dispute and never blocks care again: only an administrator's decision does.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeathDisputeEscalationScheduler {

    private final DeathDisputeService disputes;

    @SchedulerLock(name = "DeathDisputeEscalationScheduler.runSweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT5S")
    @Scheduled(cron = "${hms.mortality.dispute.escalation-cron:0 0 * * * *}", zone = "UTC")
    public void runSweep() {
        try {
            int filed = disputes.escalateUnreviewed();
            if (filed > 0) {
                log.warn("Death dispute sweep filed {} urgent review item(s)", filed);
            }
        } catch (RuntimeException ex) {
            log.error("Death dispute sweep failed: {}", ex.getMessage(), ex);
        }
    }
}
