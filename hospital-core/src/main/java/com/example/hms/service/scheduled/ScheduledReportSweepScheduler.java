package com.example.hms.service.scheduled;

import com.example.hms.service.reporting.ScheduledReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweep for scheduled reports (P3 #25a). Enabled by default — it only
 * does anything once an admin creates a report definition, so the
 * effective opt-in is the data, not the flag. Disable with
 * {@code hms.reports.enabled=false}.
 *
 * <p>Template: AppointmentReminderScheduler (thin fixed-delay sweep;
 * one bad tick never kills the scheduler thread). Hourly by default —
 * the sweep is idempotent per period, so frequency only affects
 * delivery latency after a period closes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hms.reports.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledReportSweepScheduler {

    private final ScheduledReportService scheduledReportService;

    @Scheduled(fixedDelayString = "${hms.reports.sweep-interval-ms:3600000}")
    public void runSweep() {
        try {
            int emitted = scheduledReportService.sweep();
            if (emitted > 0) {
                log.info("Scheduled-report sweep: {} report(s) emitted", emitted);
            }
        } catch (RuntimeException ex) {
            log.warn("Scheduled-report sweep failed: {}", ex.getMessage(), ex);
        }
    }
}
