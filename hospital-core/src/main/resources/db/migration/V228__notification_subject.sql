-- V228 (QA D726): a critical-result notification stayed unread after the
-- result was read back, because nothing tied the notification to the result.
-- The row now names the record it is about (the lab result), so a matching
-- read-back marks its notifications read. Older rows have none and are left
-- as they are. Plain statements, no DO block.

ALTER TABLE security.notifications
    ADD COLUMN IF NOT EXISTS subject_id UUID;

CREATE INDEX IF NOT EXISTS idx_notifications_subject
    ON security.notifications (subject_id)
    WHERE subject_id IS NOT NULL;
