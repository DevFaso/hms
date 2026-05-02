package com.example.hms.service;

import com.example.hms.enums.ReferralEventType;
import com.example.hms.enums.ReferralStatus;
import com.example.hms.model.GeneralReferral;

/**
 * Single emission point for {@code referral_events} audit rows.
 *
 * <p>Both {@link com.example.hms.service.impl.GeneralReferralServiceImpl}
 * (USER-actor transitions invoked from the controller) and
 * {@link com.example.hms.service.impl.ReferralExpiryServiceImpl}
 * (SYSTEM-actor sweep) call into this recorder so the audit-row
 * shape stays consistent across actor sources.
 */
public interface ReferralEventRecorder {

    /**
     * Record a transition triggered by an authenticated principal.
     * Reads the username off the security context; falls back to
     * {@code USER} as the {@code actorLabel}.
     */
    void recordUserEvent(GeneralReferral referral,
                         ReferralEventType type,
                         ReferralStatus fromStatus,
                         String note);

    /**
     * Record a transition triggered by a non-user writer (cron, MLLP,
     * etc.). The {@code source} is appended to {@code SYSTEM:} for the
     * {@code actorLabel} column, matching the LabResult convention.
     */
    void recordSystemEvent(GeneralReferral referral,
                           ReferralEventType type,
                           ReferralStatus fromStatus,
                           String source,
                           String note);
}
