-- V255 (QA D1126 / D1132, round 20).
-- A staff cancellation or no-show of an appointment asked for no reason on the
-- server (PUT /appointments/{id}/status?action=CANCEL with no body answered
-- 200), so nothing recorded why a booked visit was dropped. The reason given
-- with a CANCELLED or NO_SHOW move is now kept with the appointment (the
-- desk's, the close-unseen reason, the death-record cancellation, or the
-- patient's own when they gave one). Existing rows keep null: nothing was
-- recorded for them.
-- Plain statement, no DO block. Idempotent.

ALTER TABLE clinical.appointments
    ADD COLUMN IF NOT EXISTS status_reason VARCHAR(500);
