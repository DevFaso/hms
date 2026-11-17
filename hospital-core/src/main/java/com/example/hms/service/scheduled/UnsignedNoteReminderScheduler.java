package com.example.hms.service.scheduled;

import com.example.hms.service.UnsignedNoteFollowUp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A note left unsigned 24 hours after its visit closed comes back to its author: the same in-basket item becomes a
 * reminder, never a second one (provisional platform rule, tasklist.md). It never reopens or blocks the visit.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UnsignedNoteReminderScheduler {

    private final UnsignedNoteFollowUp unsignedNotes;

    @SchedulerLock(name = "UnsignedNoteReminderScheduler.runSweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT5S")
    @Scheduled(cron = "${hms.encounter-notes.unsigned-reminder-cron:0 15 * * * *}", zone = "UTC")
    public void runSweep() {
        try {
            int reminded = unsignedNotes.remindOverdue();
            if (reminded > 0) {
                log.info("Unsigned-note sweep reminded {} author(s)", reminded);
            }
        } catch (RuntimeException ex) {
            log.error("Unsigned-note sweep failed: {}", ex.getMessage(), ex);
        }
    }
}
