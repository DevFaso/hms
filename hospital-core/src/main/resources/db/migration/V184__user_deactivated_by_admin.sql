-- V184: an administrator's "switch this account off" is recorded, so it can be
-- told apart from an account that is merely not activated yet (PR #852,
-- /code-review round 6).
--
-- Under Option A every account an administrator registers starts inactive
-- until its holder confirms the invitation code. A provider admin may re-invite
-- such a member (a fresh code), and confirming it activates the account. Without
-- this column an account a super-admin or hospital admin DISABLED before its
-- first sign-in looked exactly like a pending invitee, so a provider admin could
-- re-invite it and the holder's code would switch it back on, undoing the
-- disable.
--
-- - security.users.deactivated_by_admin_at: set when an administrator turns the
--   account off (user update with active=false, soft delete); cleared only when
--   an administrator turns it back on (update with active=true, restore). While
--   it is set, a provider admin cannot re-invite the account and an invitation
--   code cannot activate it.
--
-- V181 and V182 are reserved by the external-provider plan (P2-PH, P2-LAB);
-- V183 is the MFA hotfix (PR #853). Strictly additive, idempotent, no backfill:
-- existing inactive accounts keep today's behaviour until an administrator
-- acts on them. No automated rollback is declared: the column is inert until
-- the application writes it.

ALTER TABLE security.users
    ADD COLUMN IF NOT EXISTS deactivated_by_admin_at TIMESTAMP WITHOUT TIME ZONE;
