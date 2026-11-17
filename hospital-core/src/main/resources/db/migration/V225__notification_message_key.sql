-- V225 (QA D665): a notification body was stored once, in French, and every
-- reader saw French. The row now also keeps the message key and its arguments,
-- so the body is rendered in the reader's language when it is read. The
-- French text stays in "message" for old clients and for the search filter.
-- Rows written before this have no key and keep showing their stored text.
-- Plain statements, no DO block.

ALTER TABLE security.notifications
    ADD COLUMN IF NOT EXISTS message_key VARCHAR(150);
ALTER TABLE security.notifications
    ADD COLUMN IF NOT EXISTS message_args JSONB;
