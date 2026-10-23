package com.example.hms.service.scheduled;

import com.example.hms.service.maternity.ObstetricAlertEscalationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * QA D605: sweep for URGENT obstetric and neonatal alerts nobody has
 * acknowledged. Enabled by default, a patient-safety loop. Disable with
 * {@code hms.maternity.alert-escalation.enabled=false}.
 * <p>
 * Template: {@link ProScreeningEscalationScheduler}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hms.maternity.alert-escalation.enabled", havingValue = "true", matchIfMissing = true)
public class ObstetricAlertEscalationScheduler {

    private final ObstetricAlertEscalationService escalationService;

    @SchedulerLock(name = "ObstetricAlertEscalationScheduler.runSweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT5S")
    @Scheduled(fixedDelayString = "${hms.maternity.alert-escalation.interval-ms:300000}")
    public void runSweep() {
        try {
            int sent = escalationService.escalateOverdue();
            if (sent > 0) {
                log.info("Obstetric alert escalation sweep: {} reminder(s) sent", sent);
            }
        } catch (RuntimeException ex) {
            // One bad tick must never kill the scheduler thread.
            log.warn("Obstetric alert escalation sweep failed: {}", ex.getClass().getSimpleName());
        }
    }
}
