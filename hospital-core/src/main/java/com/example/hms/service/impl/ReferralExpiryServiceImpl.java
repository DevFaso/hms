package com.example.hms.service.impl;

import com.example.hms.model.GeneralReferral;
import com.example.hms.repository.GeneralReferralRepository;
import com.example.hms.service.ReferralExpiryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReferralExpiryServiceImpl implements ReferralExpiryService {

    private static final String EXPIRY_REASON =
        "Auto-expired by SLA sweep — no acknowledgement / appointment within urgency window";

    private final GeneralReferralRepository referralRepository;

    @Override
    @Transactional
    public int expireOverdueReferrals(Duration gracePeriod) {
        final Duration grace = gracePeriod == null ? Duration.ZERO : gracePeriod;
        final LocalDateTime cutoff = LocalDateTime.now().minus(grace);

        final List<GeneralReferral> eligible = referralRepository.findExpirableReferrals(cutoff);
        if (eligible.isEmpty()) {
            log.debug("Referral expiry sweep — 0 candidates at cutoff {}", cutoff);
            return 0;
        }

        int expired = 0;
        for (GeneralReferral referral : eligible) {
            try {
                referral.expire(EXPIRY_REASON);
                expired++;
            } catch (IllegalStateException ex) {
                // Race: referral changed status between query and update. Skip and move on.
                log.warn("Referral {} skipped — state changed mid-sweep: {}",
                    referral.getId(), ex.getMessage());
            }
        }
        log.info("Referral expiry sweep — {} of {} candidate(s) expired (cutoff={}, grace={})",
            expired, eligible.size(), cutoff, grace);
        return expired;
    }
}
