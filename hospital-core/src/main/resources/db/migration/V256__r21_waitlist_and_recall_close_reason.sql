-- V256 (QA D1157, round 21).
-- Closing a waitlist entry and cancelling a recall acted on one click: the
-- server accepted an empty body, so nothing recorded why a patient left the
-- waitlist or why a planned recall was dropped. The reason is now required
-- on the server and kept with the row. Existing rows keep null: nothing was
-- recorded for them.
-- Plain statements, no DO block. Idempotent.

ALTER TABLE scheduling.appointment_waitlist
    ADD COLUMN IF NOT EXISTS close_reason VARCHAR(500);

ALTER TABLE scheduling.patient_recalls
    ADD COLUMN IF NOT EXISTS close_reason VARCHAR(500);
