package com.example.hms.service.scheduled;

import com.example.hms.service.ImagingCriticalNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweep for critical imaging findings still unacknowledged past the configured
 * delay (Tier 2 item 27). Enabled by default — this is a patient-safety loop,
 * and its default output is in-app notifications only (SMS stays behind the
 * IKODDI flag). Disable with
 * {@code hms.imaging.critical-escalation.enabled=false}.
 *
 * <p>Twin of {@code CriticalValueEscalationScheduler}, down to the interval and
 * the manual-trigger endpoint on the controller. Deliberately a separate bean
 * rather than another call inside the lab sweep: one domain failing must not
 * stop the other's alerts, and the two carry independent enable flags.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hms.imaging.critical-escalation.enabled",
    havingValue = "true", matchIfMissing = true)
public class ImagingCriticalEscalationScheduler {

    private final ImagingCriticalNotificationService imagingCriticalNotificationService;

    @Scheduled(fixedDelayString = "${hms.imaging.critical-escalation.interval-ms:300000}")
    public void runSweep() {
        try {
            int escalated = imagingCriticalNotificationService.escalateOverdue();
            if (escalated > 0) {
                log.info("Critical-imaging escalation sweep: {} report(s) escalated", escalated);
            }
        } catch (RuntimeException ex) {
            // One bad tick must never kill the scheduler thread.
            log.warn("Critical-imaging escalation sweep failed: {}", ex.getMessage(), ex);
        }
    }
}
