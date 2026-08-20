package com.example.hms.service.scheduled;

import com.example.hms.service.AppointmentReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweep for upcoming-appointment reminders (P1 #7). Enabled by default —
 * the in-app channel is always safe, and SMS stays inert until the IKODDI
 * transport is configured ({@code deliversRealSms()} guard). Disable with
 * {@code hms.appointments.reminder.enabled=false}.
 * <p>
 * Template: CriticalValueEscalationScheduler (thin fixed-delay sweep, one
 * bad tick never kills the scheduler thread). A manual-trigger twin lives
 * on AppointmentController.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hms.appointments.reminder.enabled", havingValue = "true", matchIfMissing = true)
public class AppointmentReminderScheduler {

    private final AppointmentReminderService appointmentReminderService;

    @Scheduled(fixedDelayString = "${hms.appointments.reminder.interval-ms:900000}")
    public void runSweep() {
        try {
            int reminded = appointmentReminderService.sendDueReminders();
            if (reminded > 0) {
                log.info("Appointment reminder sweep: {} patient(s) reminded", reminded);
            }
        } catch (RuntimeException ex) {
            log.warn("Appointment reminder sweep failed: {}", ex.getMessage(), ex);
        }
    }
}
