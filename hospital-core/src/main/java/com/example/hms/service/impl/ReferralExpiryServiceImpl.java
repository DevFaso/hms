package com.example.hms.service.impl;

import com.example.hms.enums.ReferralEventType;
import com.example.hms.enums.ReferralStatus;
import com.example.hms.model.GeneralReferral;
import com.example.hms.repository.GeneralReferralRepository;
import com.example.hms.service.ReferralEventRecorder;
import com.example.hms.service.ReferralExpiryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReferralExpiryServiceImpl implements ReferralExpiryService {

    private static final String EXPIRY_REASON =
        "Auto-expired by SLA sweep — no acknowledgement / appointment within urgency window";
    private static final String EVENT_SOURCE = "scheduler";

    private final GeneralReferralRepository referralRepository;
    private final ReferralEventRecorder eventRecorder;
    private final Clock clock;

    @Override
    @Transactional
    public int expireOverdueReferrals(Duration gracePeriod) {
        final LocalDateTime cutoff = computeCutoff(gracePeriod);
        return expire(referralRepository.findExpirableReferrals(cutoff), cutoff, "global");
    }

    @Override
    @Transactional
    public int expireOverdueReferralsForHospital(Duration gracePeriod, UUID hospitalId) {
        Objects.requireNonNull(hospitalId, "hospitalId is required for scoped expiry sweep");
        final LocalDateTime cutoff = computeCutoff(gracePeriod);
        return expire(
            referralRepository.findExpirableReferralsByHospital(hospitalId, cutoff),
            cutoff,
            "hospital=" + hospitalId);
    }

    private LocalDateTime computeCutoff(Duration gracePeriod) {
        // Negative durations would yield a future cutoff (now + |grace|), expiring referrals
        // before they are actually overdue — clamp to ZERO so a malformed caller is harmless.
        Duration grace = (gracePeriod == null || gracePeriod.isNegative())
            ? Duration.ZERO : gracePeriod;
        return LocalDateTime.now(clock).minus(grace);
    }

    private int expire(List<GeneralReferral> eligible, LocalDateTime cutoff, String scopeLabel) {
        if (eligible.isEmpty()) {
            log.debug("Referral expiry sweep — 0 candidates ({}) at cutoff {}", scopeLabel, cutoff);
            return 0;
        }
        int expired = 0;
        for (GeneralReferral referral : eligible) {
            ReferralStatus before = referral.getStatus();
            try {
                referral.expire(EXPIRY_REASON);
                expired++;
                eventRecorder.recordSystemEvent(
                    referral, ReferralEventType.EXPIRE, before, EVENT_SOURCE, EXPIRY_REASON);
            } catch (IllegalStateException ex) {
                // Race: referral changed status between query and update. Skip and move on.
                log.warn("Referral {} skipped — state changed mid-sweep: {}",
                    referral.getId(), ex.getMessage());
            }
        }
        log.info("Referral expiry sweep ({}) — {} of {} candidate(s) expired (cutoff={})",
            scopeLabel, expired, eligible.size(), cutoff);
        return expired;
    }
}
