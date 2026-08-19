package com.example.hms.hl7.mllp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the ADT visit-sync projection — the layer that
 * reconciles ADT^A01/A04/A08 messages against existing {@code Admission}
 * and {@code Encounter} rows.
 *
 * <p>The projection is <strong>disabled by default</strong>. The
 * demographic upsert in {@link com.example.hms.service.integration.MllpInboundAdtService}
 * continues to run regardless — turning this flag on only adds the
 * visit-reconciliation step on top of it.
 *
 * <p>Activation: set {@code APP_HL7_ADT_VISIT_SYNC_ENABLED=true} on the
 * deployment environment that is ready to attribute ADT-driven changes
 * to existing in-app Admission/Encounter rows. Conflict-resolution
 * rules are documented in {@code docs/runbooks/hl7-adt-conflict-resolution.md}.
 */
@ConfigurationProperties(prefix = "app.hl7.adt.visit-sync")
public class AdtVisitSyncProperties {

    /**
     * Master switch. When {@code false} (the default) the projection
     * service short-circuits and the ADT path behaves exactly as the
     * pre-row-24 demographics-only flow.
     */
    private boolean enabled = false;

    /**
     * When true, the projection logs a structured WARN for ADT messages
     * that arrive with a PV1-19 visit number that does not match any
     * existing Admission/Encounter. Helpful during the initial soak so
     * the integration team can see which visits HMS would need to
     * provision before turning on auto-creation in a future PR.
     *
     * <p>Default {@code true}: when sync is enabled at all, we want
     * visibility into the "would-create" workload. Set to {@code false}
     * once the noise is no longer informative.
     */
    private boolean logUnmatched = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isLogUnmatched() { return logUnmatched; }
    public void setLogUnmatched(boolean logUnmatched) { this.logUnmatched = logUnmatched; }

    /**
     * Auto-create sub-config (roadmap row 24 follow-on). Off by default;
     * gated on top of the master {@link #enabled} flag AND on top of
     * the per-hospital {@code platform.adt_intake_provider_configs.enabled}
     * column. All three must be true for the projection service to
     * actually provision an Admission on an ADT^A01 with an unmatched
     * visit-number triplet.
     *
     * <p>The three-layer gate is deliberate: master flag turns the
     * whole projection on; the auto-create sub-flag turns auto-create
     * on cluster-wide; the per-hospital row turns auto-create on for
     * one tenant. Operators can stage a rollout one hospital at a
     * time without touching cluster-wide configuration.
     */
    private final AutoCreate autoCreate = new AutoCreate();

    public AutoCreate getAutoCreate() { return autoCreate; }

    public static class AutoCreate {
        /**
         * Cluster-wide enable. Default false — auto-create is opt-in
         * even on environments where the master ADT projection
         * ({@link AdtVisitSyncProperties#enabled}) is on.
         */
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
