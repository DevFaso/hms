package com.example.hms.service;

import java.time.Duration;

/**
 * Sweeps overdue referrals and transitions them to EXPIRED via the entity
 * state-machine guard.
 *
 * <p>Scope: SUBMITTED, ACKNOWLEDGED and SCHEDULED referrals whose
 * {@code slaDueAt} fell before {@code now() - gracePeriod}. IN_PROGRESS
 * referrals are intentionally excluded — once a consultation has actually
 * begun it must terminate via {@code complete()} or {@code cancel()}.
 */
public interface ReferralExpiryService {

    /**
     * Expire every eligible referral whose SLA fell before the cutoff.
     *
     * @param gracePeriod additional buffer past {@code slaDueAt} before a
     *                    referral becomes expirable. {@link Duration#ZERO}
     *                    is acceptable (expire as soon as overdue).
     * @return the number of referrals transitioned to EXPIRED
     */
    int expireOverdueReferrals(Duration gracePeriod);
}
