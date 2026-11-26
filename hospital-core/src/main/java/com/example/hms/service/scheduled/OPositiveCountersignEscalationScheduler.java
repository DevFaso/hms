package com.example.hms.service.scheduled;

import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.service.transfusion.OPositiveReleaseNotices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * V380: what an emergency O RhD positive release still owes. The notices a release could not deliver after
 * its commit are delivered here; and a release that went out on a verbal order, or with no physician reachable,
 * is countersigned within the deadline of the hospital, or the laboratory director is told. It only tells: the
 * bag went out long ago, and nothing here holds blood.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ProvisionalRule(ClinicalRuleSetKey.EMERGENCY_O_POSITIVE_AUTHORITY)
public class OPositiveCountersignEscalationScheduler {

    private final OPositiveReleaseNotices notices;

    @SchedulerLock(name = "OPositiveCountersignEscalationScheduler.runSweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT5S")
    @Scheduled(cron = "${hms.transfusion.o-positive.countersign-escalation-cron:0 */15 * * * *}", zone = "UTC")
    public void runSweep() {
        int delivered = 0;
        int escalated = 0;
        try {
            // First what a release still owes its readers (a delivery that failed after the commit), then what
            // is past its deadline.
            for (java.util.UUID id : notices.pendingIds()) {
                delivered += deliver(id);
            }
            for (java.util.UUID id : notices.overdueCountersignIds()) {
                escalated += escalate(id);
            }
        } catch (RuntimeException ex) {
            log.error("O positive release sweep failed: {}", ex.getMessage(), ex);
        }
        if (delivered > 0 || escalated > 0) {
            log.warn("O positive release sweep filed {} owed notice(s) and {} overdue countersignature alert(s)",
                delivered, escalated);
        }
    }

    /** One row, one transaction: a row that cannot be delivered waits for the next sweep and holds no other. */
    private int deliver(java.util.UUID decisionId) {
        try {
            return notices.deliver(decisionId);
        } catch (RuntimeException ex) {
            log.error("Notices of O positive release decision {} not delivered, left to the next sweep: {}",
                decisionId, ex.getClass().getSimpleName());
            return 0;
        }
    }

    private int escalate(java.util.UUID decisionId) {
        try {
            return notices.escalateOverdueCountersign(decisionId);
        } catch (RuntimeException ex) {
            log.error("Overdue countersignature of O positive release decision {} not escalated, left to the next sweep: {}",
                decisionId, ex.getClass().getSimpleName());
            return 0;
        }
    }
}
