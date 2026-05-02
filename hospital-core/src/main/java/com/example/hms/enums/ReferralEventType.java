package com.example.hms.enums;

/**
 * Lifecycle transition emitted on the {@code referral_events} audit trail.
 * Mirrors the entity state-machine methods on {@link com.example.hms.model.GeneralReferral}.
 */
public enum ReferralEventType {
    SUBMIT,
    ACKNOWLEDGE,
    SCHEDULE,
    START,
    COMPLETE,
    CANCEL,
    REJECT,
    EXPIRE
}
