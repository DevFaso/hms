package com.example.hms.service.scheduled;

import com.example.hms.service.mail.MailOutboxDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the outbound mail outbox (V173). Thin by the house pattern: the
 * schedule lives here, the behaviour in the service. The lock keeps one
 * instance sweeping at a time; the per-row claim is what guarantees no mail
 * goes out twice should a sweep outlive the lock.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MailOutboxDispatchScheduler {

    private final MailOutboxDispatchService dispatchService;

    @SchedulerLock(name = "MailOutboxDispatchScheduler.dispatch", lockAtMostFor = "PT10M", lockAtLeastFor = "PT2S")
    @Scheduled(fixedDelayString = "${app.mail.outbox.sweep-interval-ms:10000}")
    public void dispatch() {
        try {
            dispatchService.dispatchPending();
        } catch (RuntimeException ex) {
            // An escaped exception cancels the fixed-delay schedule. Only the
            // database steps can throw here: SMTP failures (whose messages can
            // quote an address) are caught per row inside the service.
            log.error("Mail outbox sweep failed: {}", ex.getClass().getSimpleName(), ex);
        }
    }
}
