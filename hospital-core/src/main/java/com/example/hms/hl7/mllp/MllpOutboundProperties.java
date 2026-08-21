package com.example.hms.hl7.mllp;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;

/**
 * Outbound MLLP transport (P2 #17).
 *
 * <p>Mirrors {@link MllpProperties}, which configures the inbound listener. The
 * inbound half has existed since the lab module shipped; the outbound half did
 * not, so OML/ORU messages were built, queued, and never sent.
 *
 * <p>Disabled by default, like the inbound listener. An interface that starts
 * transmitting to an analyser the moment someone deploys is not a feature.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.hl7.mllp.outbound")
public class MllpOutboundProperties {

    /** Master switch. Off by default — see the class javadoc. */
    private boolean enabled = false;

    /** Analyser / middleware host to transmit to. */
    private String host = "localhost";

    private int port = 2576;

    private String charset = StandardCharsets.UTF_8.name();

    private int connectTimeoutMs = 10_000;

    /**
     * How long to wait for the ACK. An analyser that accepts the frame and never
     * acknowledges must not hold the sweep open indefinitely.
     */
    private int readTimeoutMs = 30_000;

    private int maxFrameBytes = 1_048_576;

    /**
     * Attempts before a message is parked as ERROR.
     *
     * <p>There IS a ceiling here, unlike the critical-value escalation which
     * deliberately has none. The difference is who notices: an unacknowledged
     * critical result going quiet harms a patient, whereas an outbox row
     * retrying forever against a decommissioned analyser just hides the fact
     * that the interface is broken.
     */
    private int maxAttempts = 5;

    /** Minimum wait between attempts on the same message. */
    private int retryAfterSeconds = 60;

    /** Messages transmitted per sweep, so one backlog cannot monopolise the run. */
    private int batchSize = 50;
}
