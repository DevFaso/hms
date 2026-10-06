package com.example.hms.service.integration.message;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The configured content-retention policy for
 * {@code clinical.integration_message_event}, and the one place that decides
 * whether it is actually in force.
 *
 * <p>Both the sweep ({@code IntegrationMessageRetentionScheduler}) and the
 * operator page ({@code SuperAdminIntegrationMessageServiceImpl}) read it, so
 * the page can never state a policy the sweep is refusing to run: a disabled
 * sweep, or a window it rejects, shows as "retention off" rather than as
 * windows nobody is enforcing.
 */
@Component
public class IntegrationMessageRetentionPolicy {

    private final boolean enabled;
    private final int payloadDays;
    private final int unresolvedMaxDays;

    public IntegrationMessageRetentionPolicy(
        @Value("${hms.integration.retention.enabled:true}") boolean enabled,
        @Value("${hms.integration.retention.payload-days:180}") int payloadDays,
        @Value("${hms.integration.retention.unresolved-max-days:365}") int unresolvedMaxDays
    ) {
        this.enabled = enabled;
        this.payloadDays = payloadDays;
        this.unresolvedMaxDays = unresolvedMaxDays;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Days after receipt (after resolution, for a resolved dead letter) that content is kept. */
    public int payloadDays() {
        return payloadDays;
    }

    /** Hard ceiling, in days after {@code received_at}, for the content of any failed message. */
    public int unresolvedMaxDays() {
        return unresolvedMaxDays;
    }

    /**
     * Why the configured windows cannot be enforced, or null when they can.
     * A window below one day would erase everything; a ceiling shorter than
     * the window would erase failed messages before ordinary traffic.
     */
    public String configurationProblem() {
        if (payloadDays < 1) {
            return "payload-days must be at least 1 (was " + payloadDays + ")";
        }
        if (unresolvedMaxDays < payloadDays) {
            return "unresolved-max-days (" + unresolvedMaxDays + ") must be at least payload-days ("
                + payloadDays + ")";
        }
        return null;
    }

    /** True only when the sweep is enabled and will actually run with these windows. */
    public boolean isActive() {
        return enabled && configurationProblem() == null;
    }
}
