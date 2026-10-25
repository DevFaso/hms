-- V226 (QA D665): an obstetric alert reminder rendered only its frame in the
-- reader's language; the alert sentence itself was stored once, in French.
-- The escalation row now keeps the sentence's message key and its arguments
-- (numbers only, no patient data), so every notice renders it per reader.
-- Rows raised before this keep showing their stored sentence.
-- Plain statements, no DO block.

ALTER TABLE clinical.obstetric_alert_escalations
    ADD COLUMN IF NOT EXISTS alert_message_key VARCHAR(150);
ALTER TABLE clinical.obstetric_alert_escalations
    ADD COLUMN IF NOT EXISTS alert_message_args JSONB;
