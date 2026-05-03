-- V78: Integration health snapshots (MVP-3 — gap #3 in docs/super-admin-gaps.md)
--
-- Adds a per-(integration, organization) snapshot row tracking the most recent
-- success / failure timestamp, the last status, and rolling 24h counts. The
-- super-admin Integration Health Console reads from this table joined left-outer
-- against the live PlatformIntegrationAdapter / EligibilityProvider inventory,
-- so descriptors with no recorded calls still appear in the grid as
-- last_status = NO_HISTORY.
--
-- Strictly additive (no DROP). Default org-id stays NULL for platform-wide
-- descriptors that are not yet org-scoped (BillingIntegrationAdapter /
-- EhrIntegrationAdapter / InventoryIntegrationAdapter); the eligibility
-- recorder writes one row per (integration_id, organization_id) the first
-- time a hospital under that org makes a call.
--
-- Rollback plan: dropping the table is safe — no other table references it
-- and the IntegrationHealthRecorder no-ops gracefully when the repo throws.

CREATE TABLE IF NOT EXISTS clinical.integration_health_snapshots (
    id                     UUID         PRIMARY KEY,
    integration_id         VARCHAR(100) NOT NULL,
    organization_id        UUID         NULL,
    last_status            VARCHAR(32)  NOT NULL DEFAULT 'NO_HISTORY',
    last_success_at        TIMESTAMP    NULL,
    last_failure_at        TIMESTAMP    NULL,
    last_error_message     VARCHAR(1000) NULL,
    success_count_24h      INTEGER      NOT NULL DEFAULT 0,
    failure_count_24h      INTEGER      NOT NULL DEFAULT 0,
    counts_window_started_at TIMESTAMP  NULL,
    created_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version                BIGINT       NOT NULL DEFAULT 0
);

-- Composite index for the recorder lookup path (integration + org).
CREATE INDEX IF NOT EXISTS idx_integration_health_integration_org
    ON clinical.integration_health_snapshots (integration_id, organization_id);

-- Index for the per-org console drill-down view.
CREATE INDEX IF NOT EXISTS idx_integration_health_org_status
    ON clinical.integration_health_snapshots (organization_id, last_status);

-- Index for the inventory grid sort (most-recently-active first).
CREATE INDEX IF NOT EXISTS idx_integration_health_updated_at
    ON clinical.integration_health_snapshots (updated_at DESC);
