package com.example.hms.service.scheduled;

import com.example.hms.service.webhook.WebhookDeliveryDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps pending webhook deliveries (Tier 2 item 45) — thin per the house
 * pattern: schedule here, behaviour in the service.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookDispatchScheduler {

    private final WebhookDeliveryDispatchService dispatchService;

    @Scheduled(fixedDelayString = "${app.webhooks.sweep-interval-ms:60000}")
    public void sweep() {
        try {
            dispatchService.dispatchPending();
        } catch (RuntimeException ex) {
            // An escaped exception would cancel the fixed-delay schedule
            // (the instrument-outbox lesson) - log and let the next sweep run.
            log.error("Webhook dispatch sweep failed: {}", ex.getMessage(), ex);
        }
    }
}
