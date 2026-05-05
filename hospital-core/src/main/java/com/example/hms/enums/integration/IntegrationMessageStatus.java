package com.example.hms.enums.integration;

/**
 * MVP-c3 — lifecycle status of a recorded integration message.
 * {@code FAILED} rows are what populate the dead-letter queue panel
 * and are the primary target of the replay endpoint; {@code REPLAYED}
 * marks a message that was retried and succeeded; {@code RECEIVED} /
 * {@code SENT} are healthy in-flight markers for inbound / outbound
 * traffic respectively.
 */
public enum IntegrationMessageStatus {
    /** Outbound payload was accepted by the partner / handed off cleanly. */
    SENT,

    /** Inbound payload arrived and was processed without error. */
    RECEIVED,

    /** Either direction failed (network, validation, partner reject). DLQ candidate. */
    FAILED,

    /** A previously-FAILED message was replayed successfully. */
    REPLAYED
}
