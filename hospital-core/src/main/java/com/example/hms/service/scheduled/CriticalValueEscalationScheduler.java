package com.example.hms.service.scheduled;

import com.example.hms.service.CriticalValueNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweep for critical lab results still unacknowledged past the configured
 * delay (P0 #5). Enabled by default — this is a patient-safety loop, and its
 * default output is in-app notifications only (SMS stays behind the IKODDI
 * flag). Disable with {@code hms.lab.critical-escalation.enabled=false}.
 * <p>
 * Template: PartnerResponseTimeoutScheduler (fixed-delay timeout sweep).
 * A manual-trigger twin lives on LabResultController for testing, mirroring
 * the referral-expiry pattern.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hms.lab.critical-escalation.enabled", havingValue = "true", matchIfMissing = true)
public class CriticalValueEscalationScheduler {

    private final CriticalValueNotificationService criticalValueNotificationService;

    @Scheduled(fixedDelayString = "${hms.lab.critical-escalation.interval-ms:300000}")
    public void runSweep() {
        try {
            int escalated = criticalValueNotificationService.escalateOverdue();
            if (escalated > 0) {
                log.info("Critical-value escalation sweep: {} result(s) escalated", escalated);
            }
        } catch (RuntimeException ex) {
            // One bad tick must never kill the scheduler thread.
            log.warn("Critical-value escalation sweep failed: {}", ex.getMessage(), ex);
        }
    }
}
