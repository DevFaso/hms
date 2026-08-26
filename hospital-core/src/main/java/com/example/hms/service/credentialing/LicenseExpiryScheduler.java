package com.example.hms.service.credentialing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly licence-expiry sweep (Tier 2 item 40).
 *
 * <p><b>Default ON</b> ({@code matchIfMissing = true}), matching
 * {@code AppointmentReminderScheduler} rather than the opt-in schedulers.
 * The whole finding behind this item is that the expiry data existed and
 * nothing acted on it; shipping the fix switched off by default would
 * reproduce that exactly, one config key further away. Disable per
 * environment with {@code hms.credentialing.expiry.enabled=false}.
 *
 * <p>06:00 UTC — early enough to be waiting when an administrator starts,
 * and clear of the 03:00 DHIS2 export window.
 */
@Component
@ConditionalOnProperty(
    name = "hms.credentialing.expiry.enabled",
    havingValue = "true",
    matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class LicenseExpiryScheduler {

    private final LicenseExpirySweepService sweepService;

    @Scheduled(
        cron = "${hms.credentialing.expiry.cron:0 0 6 * * *}",
        zone = "${hms.credentialing.expiry.zone:UTC}")
    public void runSweep() {
        log.info("Licence expiry sweep tick");
        try {
            int notified = sweepService.sweep();
            log.info("Licence expiry sweep completed — {} staff member(s) notified", notified);
        } catch (RuntimeException ex) {
            // Swallowed deliberately: a scheduler that throws stops nothing
            // else, but an unhandled exception here would leave no record of
            // why the sweep silently stopped producing notifications.
            log.warn("Licence expiry sweep failed: {}", ex.getMessage(), ex);
        }
    }
}
