package com.example.hms.service.pharmacy.partner;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * T-59 — Periodic escalation: reminds partner pharmacies that have not
 * replied and auto-rejects long-idle partner offers so patients can be
 * routed elsewhere.
 * <p>
 * Runs every 15 minutes. Disabled in tests via the {@code pharmacy.partner.scheduler.enabled=false}
 * property (defaults to {@code true}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PartnerResponseTimeoutScheduler {

    private final PartnerExchangeService exchangeService;

    @Scheduled(fixedDelayString = "${pharmacy.partner.scheduler.interval-ms:900000}",
               initialDelayString = "${pharmacy.partner.scheduler.initial-delay-ms:60000}")
    public void runSweep() {
        try {
            exchangeService.sweepTimeouts();
        } catch (Exception ex) {
            log.warn("Partner timeout sweep failed: {}", ex.getMessage(), ex);
        }
    }
}
