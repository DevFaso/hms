package com.example.hms.enums.integration;

/**
 * Rolled-up health state for a (integration, organization) pair as displayed in
 * the super-admin Integration Health Console.
 *
 * <ul>
 *   <li>{@link #HEALTHY}    — last call succeeded and the 24h failure count is zero</li>
 *   <li>{@link #DEGRADED}   — recent successes mixed with recent failures (failures less than half of total)</li>
 *   <li>{@link #FAILING}    — last call failed or majority of the 24h window failed</li>
 *   <li>{@link #NO_HISTORY} — the integration is registered but no calls have been recorded yet</li>
 * </ul>
 */
public enum IntegrationHealthStatus {
    HEALTHY,
    DEGRADED,
    FAILING,
    NO_HISTORY
}
