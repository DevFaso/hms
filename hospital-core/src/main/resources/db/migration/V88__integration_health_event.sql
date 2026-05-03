-- V88: Integration health time-series (MVP-c batch — MVP-3b in
-- docs/super-admin-gaps.md).
--
-- Promotes the existing point-in-time IntegrationHealthSnapshot
-- (per (integration_id, organization_id) row, last status only) to
-- a real time-series log so the Integration Health Console can render
-- a 24h sparkline. Each call site already invokes
-- IntegrationHealthRecorder.recordSuccess / recordFailure — this batch
-- adds an INSERT into the new event table alongside the existing
-- snapshot UPDATE, so the snapshot keeps powering the inventory view
-- and the event table powers the history drawer.
--
-- Strictly additive. Rollback drops the table; the recorder tolerates
-- the table being absent and falls back to snapshot-only behaviour.

CREATE TABLE IF NOT EXISTS clinical.integration_health_event (
    id              UUID         PRIMARY KEY,
    integration_id  VARCHAR(120) NOT NULL,
    organization_id UUID         NULL,
    status          VARCHAR(32)  NOT NULL,
    latency_ms      BIGINT       NULL,
    error_message   VARCHAR(1000) NULL,
    recorded_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Hot path: per-integration history filter, ordered by time DESC.
CREATE INDEX IF NOT EXISTS idx_integration_health_event_integration_time
    ON clinical.integration_health_event (integration_id, recorded_at DESC);

-- Per-org filter for the per-tenant drilldown.
CREATE INDEX IF NOT EXISTS idx_integration_health_event_org_time
    ON clinical.integration_health_event (organization_id, recorded_at DESC)
 WHERE organization_id IS NOT NULL;
