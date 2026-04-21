package com.example.hms.service.pharmacy;

import com.example.hms.enums.DispenseStatus;
import com.example.hms.model.Prescription;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.repository.pharmacy.DispenseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T-39: scheduled job that sends French refill reminder SMS to patients whose
 * treatment is about to run out. Runs once per day (configurable cron). For each
 * COMPLETED dispense in the lookback window, parses the prescription's duration
 * string for an integer number of days and, if the predicted runout falls within
 * {@code leadDays} of today, sends a reminder.
 *
 * <p>Duplicate suppression relies on the job running once per day (default 09:00
 * local time): the runout match is tested day-by-day so a given dispense triggers
 * at most one SMS during its lifecycle.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PharmacyRefillReminderScheduler {

    private static final Pattern DAYS_PATTERN = Pattern.compile("(\\d+)");

    private final DispenseRepository dispenseRepository;
    private final PharmacyServiceSupport support;

    /** Days before runout at which to send the reminder (default 3). */
    @Value("${pharmacy.refill-reminder.lead-days:3}")
    private int leadDays;

    /** Maximum age (days) of a dispense to consider for reminders (default 60). */
    @Value("${pharmacy.refill-reminder.lookback-days:60}")
    private int lookbackDays;

    @Scheduled(cron = "${pharmacy.refill-reminder.cron:0 0 9 * * *}")
    public void sendDailyRefillReminders() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = now.minusDays(lookbackDays);
        List<Dispense> recent =
                dispenseRepository.findByStatusAndDispensedAtBetween(
                        DispenseStatus.COMPLETED, from, now);
        if (recent.isEmpty()) {
            return;
        }
        LocalDate today = now.toLocalDate();
        int sent = 0;
        for (Dispense d : recent) {
            try {
                if (shouldRemindToday(d, today)) {
                    Prescription rx = d.getPrescription();
                    support.notifyRefillReminder(
                            d.getPatient(),
                            d.getMedicationName(),
                            leadDays);
                    sent++;
                }
            } catch (Exception e) {
                log.warn("Refill reminder evaluation failed for dispense {}: {}",
                        d.getId(), e.getMessage());
            }
        }
        log.info("Refill reminder job: {} reminders sent from {} candidate dispenses",
                sent, recent.size());
    }

    private boolean shouldRemindToday(Dispense d, LocalDate today) {
        if (d.getPrescription() == null || d.getDispensedAt() == null) {
            return false;
        }
        Integer days = parseDurationDays(d.getPrescription().getDuration());
        if (days == null || days <= leadDays) {
            return false;
        }
        LocalDate runout = d.getDispensedAt().plusDays(days).toLocalDate();
        long daysUntilRunout = ChronoUnit.DAYS.between(today, runout);
        return daysUntilRunout == leadDays;
    }

    /**
     * Parses a free-text duration like {@code "7 days"}, {@code "14 jours"},
     * {@code "2 semaines"} into integer days. Returns {@code null} if no digit
     * sequence is found. Weeks are converted (×7); months/years are ignored
     * (too imprecise for a day-level reminder).
     */
    static Integer parseDurationDays(String duration) {
        if (duration == null || duration.isBlank()) {
            return null;
        }
        Matcher m = DAYS_PATTERN.matcher(duration);
        if (!m.find()) {
            return null;
        }
        int n;
        try {
            n = Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        String lower = duration.toLowerCase();
        if (lower.contains("semaine") || lower.contains("week")) {
            return n * 7;
        }
        if (lower.contains("mois") || lower.contains("month")
                || lower.contains("year") || lower.contains("an")) {
            return null;
        }
        return n;
    }
}
