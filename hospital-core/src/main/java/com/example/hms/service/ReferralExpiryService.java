package com.example.hms.service;

import java.time.Duration;
import java.util.UUID;

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
     * Expire every eligible referral across all hospitals.
     *
     * <p>Used by the {@code @Scheduled} sweep (system actor) and by
     * SUPER_ADMIN-driven global runs from the admin endpoint. Hospital
     * admins must call {@link #expireOverdueReferralsForHospital} instead.
     *
     * @param gracePeriod additional buffer past {@code slaDueAt} before a
     *                    referral becomes expirable. {@link Duration#ZERO}
     *                    is acceptable; null and negative values are
     *                    treated as zero so a malformed caller can never
     *                    expire referrals before they are actually overdue.
     * @return the number of referrals transitioned to EXPIRED
     */
    int expireOverdueReferrals(Duration gracePeriod);

    /**
     * Hospital-scoped variant for the manual admin endpoint — only
     * referrals belonging to the supplied hospital are considered.
     *
     * @param gracePeriod see {@link #expireOverdueReferrals(Duration)}
     * @param hospitalId  the hospital to sweep (required)
     * @return the number of referrals transitioned to EXPIRED
     */
    int expireOverdueReferralsForHospital(Duration gracePeriod, UUID hospitalId);
}
