package com.example.hms.service.scheduled;

import com.example.hms.service.integration.InstrumentOutboxDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the instrument outbox (P2 #17).
 *
 * <p>Thin by design — the same shape as AppointmentReminderScheduler: the
 * schedule lives here, the behaviour lives in the service where it can be
 * tested without waiting for a clock.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InstrumentOutboxDispatchScheduler {

    private final InstrumentOutboxDispatchService dispatchService;

    /**
     * Every minute. Lab orders are time-sensitive — a specimen is already on its
     * way to the analyser — but not second-sensitive, and the transport is
     * disabled by default so this costs one no-op query on installations that
     * have no instrument interface.
     */
    @Scheduled(fixedDelayString = "${app.hl7.mllp.outbound.sweep-interval-ms:60000}")
    public void dispatch() {
        try {
            int sent = dispatchService.dispatchPending();
            if (sent > 0) {
                log.info("Instrument outbox: {} message(s) acknowledged", sent);
            }
        } catch (RuntimeException ex) {
            // Never propagate out of a scheduled method: an escaped exception
            // silently cancels the whole fixed-delay schedule in Spring.
            log.error("Instrument outbox dispatch sweep failed: {}", ex.getMessage(), ex);
        }
    }
}
