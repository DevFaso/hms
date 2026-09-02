package com.example.hms.service.scheduled;

import com.example.hms.service.registry.CareGapTraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly care-gap sweep (Tier 2 item 36). Enabled by default — shipping
 * defaulter tracing off-by-default would reproduce the built-but-inert
 * finding one config key further away (the item-40 lesson). The recalls it
 * creates are then picked up by the existing recall-notice sweep, so the
 * patient outreach inherits that sweep's own enablement and SMS gating.
 * Disable with {@code hms.care-gaps.enabled=false}.
 *
 * <p>Template: PatientRecallScheduler (one bad tick never kills the
 * scheduler thread). Daily cron rather than fixed-delay: a care gap ages in
 * days, not minutes, and running before the morning shift means the desk
 * opens to a current worklist.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hms.care-gaps.enabled", havingValue = "true", matchIfMissing = true)
public class CareGapScheduler {

    private final CareGapTraceService careGapTraceService;

    @Scheduled(cron = "${hms.care-gaps.cron:0 30 5 * * *}")
    public void runSweep() {
        try {
            careGapTraceService.traceDefaulters();
        } catch (RuntimeException ex) {
            log.warn("Care-gap sweep failed: {}", ex.getMessage(), ex);
        }
    }
}
