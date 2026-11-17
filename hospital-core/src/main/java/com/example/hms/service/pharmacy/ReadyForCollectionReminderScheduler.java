package com.example.hms.service.pharmacy;

import com.example.hms.enums.DispenseStatus;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.repository.pharmacy.DispenseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * G15 AC-13 (user decision 2): one "still waiting for you" SMS for a fill
 * prepared more than {@code pharmacy.ready-for-collection.reminder-after}
 * (default P3D) ago and not yet collected. Daily; nothing is ever cancelled
 * automatically.
 *
 * <p>Exactly once per fill, across instances and reruns: the ShedLock lock
 * keeps two instances from sweeping together, and each fill is CLAIMED
 * (a conditional stamp of {@code ready_reminder_sent_at}, committed in its
 * own transaction by {@link ReadyReminderClaimService}) before its SMS goes
 * out after that commit. A fill handed over, cancelled or voided in the
 * meantime is no longer PENDING, so the claim fails and nothing is sent.
 * {@code void} on purpose: ShedLock refuses primitive-returning methods
 * (#563).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReadyForCollectionReminderScheduler {

    private final DispenseRepository dispenseRepository;
    private final ReadyReminderClaimService claimService;
    private final Clock clock;

    /** {@code pharmacy.ready-for-collection.reminder.enabled}: the reminder's own switch. */
    @Value("${pharmacy.ready-for-collection.reminder.enabled:true}")
    private boolean enabled = true;

    @Value("${pharmacy.ready-for-collection.reminder-after:P3D}")
    private Duration reminderAfter = Duration.ofDays(3);

    @SchedulerLock(name = "ReadyForCollectionReminderScheduler.sendReminders",
            lockAtMostFor = "PT30M", lockAtLeastFor = "PT5S")
    @Scheduled(cron = "${pharmacy.ready-for-collection.reminder.cron:0 0 10 * * *}")
    public void sendReminders() {
        if (!enabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<UUID> due = dispenseRepository
                .findByStatusAndReadyReminderSentAtIsNullAndCreatedAtBefore(DispenseStatus.PENDING,
                        now.minus(reminderAfter))
                .stream()
                .map(Dispense::getId)
                .toList();
        int sent = 0;
        for (UUID dispenseId : due) {
            try {
                if (claimService.claimAndNotify(dispenseId, now)) {
                    sent++;
                }
            } catch (RuntimeException e) {
                log.warn("Ready-for-collection reminder failed for dispense {}: {}", dispenseId, e.getMessage());
            }
        }
        if (!due.isEmpty()) {
            log.info("Ready-for-collection reminders: {} sent from {} waiting fills", sent, due.size());
        }
    }
}
