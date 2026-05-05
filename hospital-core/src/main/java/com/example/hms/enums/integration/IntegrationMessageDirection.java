package com.example.hms.enums.integration;

/**
 * MVP-c3 — wire-direction of a recorded integration message. Used by
 * the Bridges-style search + DLQ surface so an operator can ask "did
 * the outbound claim leave the system?" or "what came back from the
 * payer?" without scanning the payload.
 */
public enum IntegrationMessageDirection {
    /** Message we sent to the partner (claim, eligibility request, ADT). */
    OUTBOUND,

    /** Message we received from the partner (eligibility response, ack, error). */
    INBOUND
}
