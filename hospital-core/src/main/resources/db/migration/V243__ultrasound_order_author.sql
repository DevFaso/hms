-- V243 (round 16, sibling fixes).
--
-- QA D930 class: an ultrasound order stored who placed it as a display name
-- only (ordered_by), so the server could not tell the ordering clinician from
-- any colleague: every doctor or midwife at the hospital could rewrite or
-- cancel it. The ordering user's id is now recorded on create; update and
-- cancel are refused to anyone else (a super-admin in global view excepted).
-- Rows written before this column keep the old rule (null = no recorded
-- author).
--
-- Schema only, no data change, no PHI (a user id). Strictly additive, plain
-- statements, no DO block. Idempotent. No automated rollback is declared.

ALTER TABLE clinical.ultrasound_orders
    ADD COLUMN IF NOT EXISTS ordered_by_user_id UUID;
