package com.example.hms.service.mail;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound mail outbox knobs (V173). The sweep is on by default: with no rows
 * it costs one indexed query, and a deployment without a mail transport never
 * queues anything (the enqueue refuses with NOT_CONFIGURED instead).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.mail.outbox")
public class MailOutboxProperties {

    /** Master switch for the dispatch sweep. Enqueueing is not affected. */
    private boolean enabled = true;

    /**
     * How often the sweep looks for due mail. Short on purpose: an activation
     * code or a password-reset link is waited for by a person at a screen.
     */
    private long sweepIntervalMs = 10_000;

    /** Messages per sweep. */
    private int batchSize = 25;

    /** Attempts per message, then terminal FAILED. */
    private int maxAttempts = 6;

    /** Wait after the first failure; doubles on each further one. */
    private long initialBackoffSeconds = 30;

    /** Ceiling of the doubling backoff. */
    private long maxBackoffSeconds = 3_600;

    /**
     * How long a claimed row is reserved for the instance sending it. Must
     * outlast the SMTP timeouts, or a slow send could be claimed again.
     */
    private long claimLeaseSeconds = 300;

    /** Terminal rows (bodies already dropped) are deleted after this many days. */
    private int retentionDays = 30;
}
