package com.example.hms.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feature flag for per-tenant cost observability (roadmap row 44,
 * v2.0 / Operations).
 *
 * <p>Two surfaces co-evolve under this flag:
 * <ul>
 *   <li>The chargeback report endpoint
 *       ({@code GET /api/super-admin/cost/per-tenant}) — surfaced via
 *       {@link com.example.hms.controller.ChargebackReportController}
 *       when the flag is on; returns 404 when off.</li>
 *   <li>Tenant tags on Splunk events + Grafana series — the existing
 *       audit logger already carries {@code hospital_id} on every
 *       audit row, and the OTLP exporter ships the per-tenant tag via
 *       MDC on instrumented logging. The flag gates the
 *       <em>operator-facing</em> rollup; raw per-tenant tags on
 *       observability events are unconditional.</li>
 * </ul>
 *
 * <p>Default {@code false} so deployments must opt in explicitly. The
 * row-44 follow-on adds the full chargeback algorithm (currency
 * mapping per hospital, Splunk-event-count + Grafana-series-cardinality
 * inputs) and a UI panel in the super-admin Control Tower.
 */
@ConfigurationProperties(prefix = "app.observability.tenant-cost")
public class TenantCostObservabilityProperties {

    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
