-- V232 (QA D776): an SBAR handoff named nobody to receive it, and its author
-- could complete it herself. A handoff now records who wrote it and who it is
-- handed to; only that recipient completes it. Rows written before this have
-- neither and stay completable as before. Plain statements, no DO block.

ALTER TABLE clinical.nurse_handoffs
    ADD COLUMN IF NOT EXISTS created_by_user_id UUID;

ALTER TABLE clinical.nurse_handoffs
    ADD COLUMN IF NOT EXISTS recipient_user_id UUID;

ALTER TABLE clinical.nurse_handoffs
    ADD COLUMN IF NOT EXISTS recipient_name VARCHAR(200);

CREATE INDEX IF NOT EXISTS idx_nurse_handoffs_recipient
    ON clinical.nurse_handoffs (recipient_user_id)
    WHERE recipient_user_id IS NOT NULL;
