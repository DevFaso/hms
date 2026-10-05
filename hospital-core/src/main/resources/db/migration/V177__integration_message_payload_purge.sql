-- V177: retention for the CONTENT of clinical.integration_message_event.
--
-- User decision 2026-10-04: the event rows (ids, types, statuses,
-- timestamps, sender, control ids) are audit evidence and stay
-- indefinitely; the stored message body (the encrypted payload, which
-- can hold a whole HL7 message, PID and all) is erased once it is older
-- than a configurable window (hms.integration.retention.payload-days,
-- default 180). A resolved dead letter loses it that many days after
-- resolution; an unresolved one keeps it while it is replayable, up to
-- hms.integration.retention.unresolved-max-days (default 365).
-- The sweep is IntegrationMessageRetentionScheduler.
--
-- Strictly additive:
--   * payload_purged_at marks a row whose body was erased, so the
--     operator page and the replay endpoint can say "content purged"
--     instead of showing a row that looks like it never had one (a
--     refusal recorded by an inbound service legitimately has no body).
--   * a partial index over the rows that still hold a body, so the
--     nightly sweep reads only those; it shrinks as bodies are purged.

ALTER TABLE clinical.integration_message_event
    ADD COLUMN IF NOT EXISTS payload_purged_at TIMESTAMP NULL;

CREATE INDEX IF NOT EXISTS idx_integration_message_event_payload_held
    ON clinical.integration_message_event (received_at)
 WHERE payload IS NOT NULL;
