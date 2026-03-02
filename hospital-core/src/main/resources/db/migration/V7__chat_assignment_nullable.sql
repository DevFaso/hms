-- Allow chat messages without a role-assignment context (e.g. cross-hospital
-- SUPER_ADMIN direct messages sent without specifying a hospitalName).
ALTER TABLE support.chat_messages
    ALTER COLUMN assignment_id DROP NOT NULL;
