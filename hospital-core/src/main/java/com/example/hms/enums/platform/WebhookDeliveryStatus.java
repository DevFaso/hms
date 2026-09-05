package com.example.hms.enums.platform;

/**
 * One webhook delivery attempt chain (Tier 2 item 45) — the
 * instrument-outbox vocabulary: PENDING rows are swept and retried until
 * they hit the attempt ceiling, then land terminally in ERROR.
 */
public enum WebhookDeliveryStatus {
    PENDING,
    SENT,
    ERROR
}
