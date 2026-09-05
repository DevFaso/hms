package com.example.hms.service.webhook;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound webhook delivery knobs (Tier 2 item 45). Dispatch defaults ON:
 * registering an endpoint is itself the opt-in — with no rows the sweep
 * is a no-op, so there is nothing to gate at boot (unlike MLLP, whose
 * listener binds a port).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.webhooks")
public class WebhookProperties {

    /** Master switch for the dispatch sweep. */
    private boolean enabled = true;

    /** How often the sweep looks for pending deliveries. */
    private long sweepIntervalMs = 60_000;

    /** Attempt ceiling per delivery, then terminal ERROR (V119 vocabulary). */
    private int maxAttempts = 8;

    /** Wait between attempts on one delivery. */
    private long retryAfterSeconds = 300;

    /** Deliveries per sweep. */
    private int batchSize = 50;

    /** Terminal failures in a row before an endpoint auto-disables. */
    private int failureDisableThreshold = 20;

    private int connectTimeoutMs = 5_000;
    private int readTimeoutMs = 10_000;
}
