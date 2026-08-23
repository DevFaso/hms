package com.example.hms.service.scheduled;

import com.example.hms.service.scheduling.RecallReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweep for due recall notices (P3 #22). Enabled by default — the in-app
 * channel is always safe, and SMS stays inert until the IKODDI transport is
 * configured. Disable with {@code hms.recalls.notice.enabled=false}.
 *
 * <p>Template: AppointmentReminderScheduler (thin fixed-delay sweep; one bad
 * tick never kills the scheduler thread).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hms.recalls.notice.enabled", havingValue = "true", matchIfMissing = true)
public class PatientRecallScheduler {

    private final RecallReminderService recallReminderService;

    @Scheduled(fixedDelayString = "${hms.recalls.notice.interval-ms:3600000}")
    public void runSweep() {
        try {
            int notified = recallReminderService.sendDueRecallNotices();
            if (notified > 0) {
                log.info("Recall notice sweep: {} patient(s) notified", notified);
            }
        } catch (RuntimeException ex) {
            log.warn("Recall notice sweep failed: {}", ex.getMessage(), ex);
        }
    }
}
