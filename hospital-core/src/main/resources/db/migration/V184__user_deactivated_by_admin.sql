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
-- Backfill (round 7): an account switched off BEFORE this column existed has
-- no marker, and would read as a pending invitee. So an existing inactive
-- account is marked when it is soft-deleted, or when nothing is pending on it:
-- no invitation code waiting to be confirmed on any of its assignments, and no
-- email-activation token. Those two are what a genuine invitee or a self-signup
-- awaiting verification holds, so neither is touched. The stamp is the row's
-- last update (when the disable most likely happened).
--
-- V181 and V182 are reserved by the external-provider plan (P2-PH, P2-LAB);
-- V183 is the MFA hotfix (PR #853). Additive, idempotent (the backfill only
-- fills NULLs). No automated rollback is declared.

ALTER TABLE security.users
    ADD COLUMN IF NOT EXISTS deactivated_by_admin_at TIMESTAMP WITHOUT TIME ZONE;

UPDATE security.users u
SET deactivated_by_admin_at = COALESCE(u.updated_at, CURRENT_TIMESTAMP)
WHERE u.deactivated_by_admin_at IS NULL
  AND u.is_active = FALSE
  AND (
        u.is_deleted = TRUE
        OR (
            u.activation_token IS NULL
            AND NOT EXISTS (
                SELECT 1
                FROM security.user_role_hospital_assignment a
                WHERE a.user_id = u.id
                  AND a.confirmation_code IS NOT NULL
                  AND a.confirmation_verified_at IS NULL
            )
        )
      );
