-- V71: P1 #12 follow-up — ReferralEvent audit trail
--
-- Append-only history of every state-machine transition on a referral
-- (submit / acknowledge / schedule / start / complete / cancel / reject / expire).
-- One row is written per transition; rows are never updated.
--
-- referral_id is a raw UUID (no FK) so the audit trail survives if a
-- referral is hard-deleted, mirroring the encounter_note_history pattern.
-- actor_label uses the LabResult system-actor convention: USER for an
-- authenticated principal, SYSTEM:<source> for non-user writers (e.g.
-- the @Scheduled SLA sweep).

CREATE TABLE IF NOT EXISTS public.referral_events (
    id              UUID PRIMARY KEY,
    referral_id     UUID NOT NULL,
    event_type      VARCHAR(20) NOT NULL,
    from_status     VARCHAR(20),
    to_status       VARCHAR(20) NOT NULL,
    actor_username  VARCHAR(255),
    actor_label     VARCHAR(100) NOT NULL,
    note            TEXT,
    recorded_at     TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_referral_events_referral
    ON public.referral_events (referral_id, recorded_at);

CREATE INDEX IF NOT EXISTS idx_referral_events_type
    ON public.referral_events (event_type);

-- Rollback (manual only):
--   DROP INDEX IF EXISTS public.idx_referral_events_type;
--   DROP INDEX IF EXISTS public.idx_referral_events_referral;
--   DROP TABLE IF EXISTS public.referral_events;
