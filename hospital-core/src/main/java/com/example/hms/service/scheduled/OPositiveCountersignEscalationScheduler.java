package com.example.hms.service.scheduled;

import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.service.transfusion.OPositiveReleaseAuthorityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * V380: an emergency O RhD positive release that went out on a verbal order, or with no physician reachable,
 * is countersigned within the deadline of the hospital, or the laboratory director is told. It only tells: the
 * bag went out long ago, and nothing here holds blood.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ProvisionalRule(ClinicalRuleSetKey.EMERGENCY_O_POSITIVE_AUTHORITY)
public class OPositiveCountersignEscalationScheduler {

    private final OPositiveReleaseAuthorityService authority;

    @SchedulerLock(name = "OPositiveCountersignEscalationScheduler.runSweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT5S")
    @Scheduled(cron = "${hms.transfusion.o-positive.countersign-escalation-cron:0 */15 * * * *}", zone = "UTC")
    public void runSweep() {
        try {
            int filed = authority.escalateOverdueCountersigns();
            if (filed > 0) {
                log.warn("O positive countersignature sweep filed {} urgent item(s)", filed);
            }
        } catch (RuntimeException ex) {
            log.error("O positive countersignature sweep failed: {}", ex.getMessage(), ex);
        }
    }
}
