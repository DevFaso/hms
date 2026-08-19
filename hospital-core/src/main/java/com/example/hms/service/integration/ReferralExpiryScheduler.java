package com.example.hms.service.integration;

import com.example.hms.service.ReferralExpiryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Periodically transitions overdue {@code SUBMITTED}/{@code ACKNOWLEDGED}/
 * {@code SCHEDULED} referrals to {@code EXPIRED} so the worklists stop
 * showing perpetually-stale items.
 *
 * <p>Default OFF — toggled per environment via
 * {@code hms.referrals.expiry.enabled=true}. The default cron fires at
 * 03:00 UTC daily, after the DHIS2 export window. Override via
 * {@code hms.referrals.expiry.cron}.
 *
 * <p>{@code hms.referrals.expiry.grace-hours} (default 0) gives operators
 * a safety buffer past the entity-computed {@code slaDueAt} before the
 * sweep claims a referral.
 */
@Component
@ConditionalOnProperty(name = "hms.referrals.expiry.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class ReferralExpiryScheduler {

    private final ReferralExpiryService expiryService;

    @Value("${hms.referrals.expiry.grace-hours:0}")
    private long graceHours;

    @Scheduled(
        cron = "${hms.referrals.expiry.cron:0 0 3 * * *}",
        zone = "${hms.referrals.expiry.zone:UTC}")
    public void runSweep() {
        final Duration grace = Duration.ofHours(Math.max(0L, graceHours));
        log.info("Referral expiry scheduler tick (grace={}h)", grace.toHours());
        try {
            int expired = expiryService.expireOverdueReferrals(grace);
            log.info("Referral expiry sweep finished — {} referral(s) expired", expired);
        } catch (RuntimeException ex) {
            log.warn("Referral expiry sweep failed: {}", ex.getMessage(), ex);
        }
    }
}
