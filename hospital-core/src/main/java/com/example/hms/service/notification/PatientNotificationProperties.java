package com.example.hms.service.notification;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Patient notification delivery knobs (V312). The sweep is on by default: with no rows it costs
 * one indexed query, and a deployment with no SMS or mail transport queues nothing.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.patient-notifications")
public class PatientNotificationProperties {

    /** Master switch for the dispatch sweep. Queueing is not affected. */
    private boolean enabled = true;

    /** How often the sweep looks for due sends. */
    private long sweepIntervalMs = 30_000;

    /** Sends per sweep. */
    private int batchSize = 25;

    /** Attempts per send, then terminal FAILED. Bounded on purpose: every SMS is paid. */
    private int maxAttempts = 5;

    /** Wait after the first transient failure; doubles on each further one. */
    private long initialBackoffSeconds = 60;

    /** Ceiling of the doubling backoff. */
    private long maxBackoffSeconds = 3_600;

    /**
     * {@code EMAIL_RECIPIENT_ALLOWLIST}: when set, the only addresses the ledger's emails may go to
     * ({@link EmailRecipientAllowlist}). Blank (production) means no restriction.
     */
    private String emailRecipientAllowlist = "";

    /** How long a claimed row is reserved for the instance sending it. Must outlast the transport timeouts. */
    private long claimLeaseSeconds = 300;
}
