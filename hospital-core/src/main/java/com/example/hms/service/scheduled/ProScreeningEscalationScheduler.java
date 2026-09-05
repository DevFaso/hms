package com.example.hms.service.scheduled;

import com.example.hms.service.pro.ProScreeningEscalationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweep for self-harm-positive screening responses still unacknowledged
 * past the configured delay (Tier 2 item 47). Enabled by default — a
 * patient-safety loop, in-app only unless SMS is live. Disable with
 * {@code hms.pro.critical-escalation.enabled=false}.
 * <p>
 * Template: {@link CriticalValueEscalationScheduler}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hms.pro.critical-escalation.enabled", havingValue = "true", matchIfMissing = true)
public class ProScreeningEscalationScheduler {

    private final ProScreeningEscalationService escalationService;

    @Scheduled(fixedDelayString = "${hms.pro.critical-escalation.interval-ms:300000}")
    public void runSweep() {
        try {
            int escalated = escalationService.escalateOverdue();
            if (escalated > 0) {
                log.info("PRO screening escalation sweep: {} response(s) escalated", escalated);
            }
        } catch (RuntimeException ex) {
            // One bad tick must never kill the scheduler thread.
            log.warn("PRO screening escalation sweep failed: {}", ex.getMessage(), ex);
        }
    }
}
