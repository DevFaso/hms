package com.example.hms.enums.platform;

/**
 * Lifecycle of an outbound webhook endpoint (Tier 2 item 45). Revoked,
 * never deleted — the delivery history hangs off the row.
 */
public enum WebhookEndpointStatus {
    ACTIVE,
    /** Manually paused by an admin; deliveries stop enqueuing. */
    PAUSED,
    /**
     * Automatically disabled after too many consecutive terminal delivery
     * failures — a dead receiver must not accumulate an unbounded queue.
     * An admin re-enables it explicitly.
     */
    DISABLED_FAILURES,
    REVOKED
}
