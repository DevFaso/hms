package com.example.hms.service.scheduled;

import com.example.hms.service.notification.PatientNotificationDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the patient notification ledger (V312). Thin by the house pattern: the schedule lives
 * here, the behaviour in the service. The lock keeps one instance sweeping at a time; the per-row
 * claim is what guarantees no notification goes out twice should a sweep outlive the lock.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PatientNotificationDispatchScheduler {

    private final PatientNotificationDispatchService dispatchService;

    @SchedulerLock(name = "PatientNotificationDispatchScheduler.dispatch", lockAtMostFor = "PT10M",
        lockAtLeastFor = "PT2S")
    @Scheduled(fixedDelayString = "${app.patient-notifications.sweep-interval-ms:30000}")
    public void dispatch() {
        try {
            dispatchService.dispatchPending();
        } catch (RuntimeException ex) {
            // An escaped exception cancels the fixed-delay schedule. Transport failures are caught
            // per row inside the service; only a database step can reach here.
            log.error("Patient notification sweep failed: {}", ex.getClass().getSimpleName(), ex);
        }
    }
}
