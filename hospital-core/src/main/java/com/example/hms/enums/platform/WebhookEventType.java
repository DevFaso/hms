package com.example.hms.enums.platform;

/**
 * Events a third-party endpoint can subscribe to (Tier 2 item 45).
 *
 * <p>Deliberately small: each value must have a REAL emitter wired the day
 * it is added (the built-but-unreachable defect class), and payloads are
 * thin id-references — the receiver fetches details through the
 * authenticated API, so no PHI rides in a webhook body.
 */
public enum WebhookEventType {
    /** The connectivity test an admin can fire from the endpoint's page. */
    PING,
    APPOINTMENT_BOOKED,
    APPOINTMENT_CANCELLED,
    APPOINTMENT_RESCHEDULED
}
