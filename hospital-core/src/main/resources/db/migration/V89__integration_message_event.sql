-- V89: Bridges-style per-message log + DLQ (MVP-c3 — Tier 2 #5
-- in docs/platform-management-gaps-vs-epic.md).
--
-- IntegrationHealthEvent (V88) records *probe* outcomes — one row
-- per "did this integration work right now?". This table records
-- *messages* — one row per actual partner protocol payload (HL7
-- ADT, FHIR Bundle, claim batch). It powers the Bridges-style
-- search + dead-letter-queue + replay surface that operators need
-- the moment a real partner connector goes live and a claim fails.
--
-- Strictly additive. The recorder tolerates the table being absent
-- (best-effort INSERT) so a rollback is safe.

CREATE TABLE IF NOT EXISTS clinical.integration_message_event (
    id               UUID         PRIMARY KEY,
    integration_id   VARCHAR(120) NOT NULL,
    organization_id  UUID         NULL,
    direction        VARCHAR(16)  NOT NULL,
    message_type     VARCHAR(64)  NULL,
    correlation_id   VARCHAR(120) NULL,
    payload          TEXT         NULL,
    status           VARCHAR(32)  NOT NULL,
    error_message    VARCHAR(2000) NULL,
    attempt_count    INTEGER      NOT NULL DEFAULT 1,
    last_attempted_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    received_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Per-integration timeline (search by integration + status + time).
CREATE INDEX IF NOT EXISTS idx_integration_message_event_integration_time
    ON clinical.integration_message_event (integration_id, received_at DESC);

-- DLQ scan — operators frequently filter to FAILED messages.
CREATE INDEX IF NOT EXISTS idx_integration_message_event_status_time
    ON clinical.integration_message_event (status, received_at DESC);

-- Per-org drilldown (only on rows that carry an org).
CREATE INDEX IF NOT EXISTS idx_integration_message_event_org_time
    ON clinical.integration_message_event (organization_id, received_at DESC)
 WHERE organization_id IS NOT NULL;

-- Correlation lookup — replay flows update the same correlation id
-- so an operator can trace the full lifecycle of one message.
CREATE INDEX IF NOT EXISTS idx_integration_message_event_correlation
    ON clinical.integration_message_event (correlation_id)
 WHERE correlation_id IS NOT NULL;
