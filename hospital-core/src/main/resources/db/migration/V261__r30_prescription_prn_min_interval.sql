-- V261 (QA round 30, user decision 2026-11-02): an as-needed ("PRN",
-- "si besoin") prescription carries the minimum interval between two doses,
-- in hours, as the prescriber writes it on the order. The MAR offers the next
-- dose once that interval has passed since the last dose given; a dose given
-- sooner records why. Required on a new as-needed order (the request is
-- refused without it); no per-drug default is ever written, so existing
-- as-needed orders keep a null here and the MAR asks a reason for each later
-- dose of them. Plain statement, no DO block. Idempotent.

ALTER TABLE clinical.prescriptions
    ADD COLUMN IF NOT EXISTS prn_min_interval_hours INTEGER;
