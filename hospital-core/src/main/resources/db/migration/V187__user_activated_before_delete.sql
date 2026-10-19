-- V187: a soft delete records whether the account was activated (PR #872,
-- defect D21, /code-review round 2).
--
-- The delete switches the account and every assignment off, so afterwards
-- nothing tells a patient verified by e-mail who never signed in from a staff
-- invitee who never entered their code: both have a sent, unconfirmed row.
-- The restore must bring the first back active and keep the second inactive
-- with a fresh code.
--
-- - security.users.activated_before_delete: written by the soft delete (true
--   when the account was active, had signed in, or had a confirmed
--   assignment), read and cleared by the restore. NULL = not deleted, or
--   deleted before this column existed (the restore then falls back to
--   guessing from the rows).
--
-- No backfill: for an account already deleted, its pre-delete state is gone.
-- V185 and V186 are taken by other branches (phone E.164 backfill, Sage export
-- plan). Strictly additive, idempotent; inert until written.

ALTER TABLE security.users
    ADD COLUMN IF NOT EXISTS activated_before_delete BOOLEAN;
