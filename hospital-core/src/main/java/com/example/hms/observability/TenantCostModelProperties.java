package com.example.hms.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.math.BigDecimal;

/**
 * Per-deployment chargeback cost model (roadmap row 44 follow-on).
 *
 * <p>The foundation pass on {@code ChargebackReportService} returned
 * raw audit-event counts. This layer converts the counts (and the
 * future Splunk / Grafana / Postgres inputs) into a per-tenant
 * monetary amount using a four-component linear model:
 *
 * <pre>
 *   amount = (audit_events    × per-audit-event)
 *          + (splunk_events   × per-splunk-event)
 *          + (grafana_series  × per-grafana-series)
 *          + (storage_bytes   × per-byte) ÷ 1_073_741_824   // (per-GiB unit on the property)
 * </pre>
 *
 * <p>All four rates default to zero — a deployment that hasn't
 * configured a rate sees a chargeback amount of zero rather than a
 * surprise bill. The currency code is informational (no FX
 * conversion done here); the UI renders it next to the amount.
 *
 * <p>Operators set the rates per environment via env vars:
 * {@code APP_OBSERVABILITY_TENANT_COST_MODEL_*}.
 */
@ConfigurationProperties(prefix = "app.observability.tenant-cost.model")
public class TenantCostModelProperties {

    /**
     * ISO 4217 currency code. Defaults to USD; deployments operating in
     * West Africa typically override to XOF (CFA franc). HMS does no
     * FX conversion — the report carries whatever currency the operator
     * configured.
     */
    private String currency = "USD";

    @NestedConfigurationProperty
    private final Rates rates = new Rates();

    public static class Rates {
        /** Monetary amount per persisted audit-event row. */
        private BigDecimal perAuditEvent = BigDecimal.ZERO;

        /** Monetary amount per Splunk event (follow-on input). */
        private BigDecimal perSplunkEvent = BigDecimal.ZERO;

        /**
         * Monetary amount per Grafana / Prometheus time-series the
         * tenant contributes (follow-on input). Series cardinality
         * is sampled via {@code prometheus_tsdb_head_series} divided
         * by the per-tenant relabel.
         */
        private BigDecimal perGrafanaSeries = BigDecimal.ZERO;

        /**
         * Monetary amount per GiB of Postgres storage the tenant's
         * tables occupy (follow-on input). The cost model multiplies
         * the byte count by this rate divided by 2^30 so the property
         * stays in per-GiB units rather than per-byte fractions.
         */
        private BigDecimal perStorageGib = BigDecimal.ZERO;

        public BigDecimal getPerAuditEvent() { return perAuditEvent; }
        public void setPerAuditEvent(BigDecimal v) { this.perAuditEvent = v == null ? BigDecimal.ZERO : v; }
        public BigDecimal getPerSplunkEvent() { return perSplunkEvent; }
        public void setPerSplunkEvent(BigDecimal v) { this.perSplunkEvent = v == null ? BigDecimal.ZERO : v; }
        public BigDecimal getPerGrafanaSeries() { return perGrafanaSeries; }
        public void setPerGrafanaSeries(BigDecimal v) { this.perGrafanaSeries = v == null ? BigDecimal.ZERO : v; }
        public BigDecimal getPerStorageGib() { return perStorageGib; }
        public void setPerStorageGib(BigDecimal v) { this.perStorageGib = v == null ? BigDecimal.ZERO : v; }
    }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency == null ? "USD" : currency; }
    public Rates getRates() { return rates; }
}
