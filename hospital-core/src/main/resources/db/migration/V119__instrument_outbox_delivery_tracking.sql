-- =============================================================
-- V119: delivery tracking for the instrument outbox.
--
-- WHY
--   OML and ORU messages have been built and queued in
--   lab.instrument_outbox since the lab module shipped, and never
--   transmitted — there was no sender. The row went in PENDING and
--   stayed there. Every "sent to the analyser" claim upstream was
--   about a row in a table.
--
--   The inbound half is complete (MllpTcpServer, MllpFrameCodec, an
--   allowed-senders allowlist). This adds the missing outbound
--   half, and a sender that retries needs somewhere to record why
--   it is retrying.
--
--   attempts       — how many transmissions have been tried, so a
--                    permanently unreachable analyser stops being
--                    retried forever and starts being visible.
--   last_error     — the failure text, because "status = ERROR" with
--                    no reason is a dead end for whoever has to fix
--                    the interface at 3am.
--   last_attempt_at— what the retry backoff is measured from.
--
-- All additive with defaults, so re-running is a no-op and existing
-- PENDING rows simply start at zero attempts.
-- =============================================================

ALTER TABLE lab.instrument_outbox
    ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE lab.instrument_outbox
    ADD COLUMN IF NOT EXISTS last_error VARCHAR(2000);

ALTER TABLE lab.instrument_outbox
    ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMP WITHOUT TIME ZONE;

-- The dispatch sweep selects on (status, last_attempt_at); without
-- this it scans the whole outbox every run, and the outbox only
-- grows.
CREATE INDEX IF NOT EXISTS idx_instrument_outbox_dispatch
    ON lab.instrument_outbox (status, last_attempt_at);
