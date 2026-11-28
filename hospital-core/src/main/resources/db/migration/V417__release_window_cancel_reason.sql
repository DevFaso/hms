-- V417: a cancelled release window keeps the reason it was cancelled for
-- (QA r81 D4082). Null on every window that is not cancelled, and on the
-- windows cancelled before this column existed (none: cancelling arrives with
-- this release). Additive and idempotent; no rollback declared.

ALTER TABLE platform.platform_release_windows
    ADD COLUMN IF NOT EXISTS cancel_reason VARCHAR(500);
