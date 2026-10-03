package com.example.hms.enums.platform;

/**
 * One queued outbound mail. PENDING rows are swept and retried with a growing
 * backoff until the attempt ceiling, then land terminally in FAILED; SENT means
 * the SMTP server accepted the message. Both terminal states drop the body.
 */
public enum MailOutboxStatus {
    PENDING,
    SENT,
    FAILED
}
