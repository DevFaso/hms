-- V183: replacing an MFA authenticator keeps the current one working until
-- the new one is confirmed (security hotfix PR #853, /code-review round 1).
--
-- Before this, starting a re-enrolment overwrote the TOTP secret and
-- unverified the row, so the account looked unenrolled until the new code was
-- confirmed: an abandoned re-enrolment either locked the user out or handed
-- the account back to the password-only first-enrolment path.
--
-- - security.user_mfa_enrollments.pending_totp_secret: the secret of a
--   replacement that has been started but not confirmed (encrypted by the
--   application, like totp_secret). The current secret stays in totp_secret
--   and keeps verifying; verify-enrollment swaps the two.
-- - security.mfa_backup_codes.pending: the backup codes issued with that
--   replacement. They are not accepted until it is confirmed, when they
--   replace the current set.
--
-- - security.user_mfa_enrollments.last_totp_step: the TOTP time-step of the
--   last accepted code, so a code is accepted once (login or step-up), never
--   replayed inside its 30-90 s window (round 4).
-- - At most one TOTP row per user (round 4): two concurrent first enrolments
--   could each insert one, after which every lookup by (user, TOTP) throws.
--   Existing duplicates are removed first, keeping the best row (verified,
--   enabled, most recently verified, most recently updated), then a partial
--   unique index enforces it. Other methods may legitimately have several
--   rows (one per channel), so the index covers TOTP only.
--
-- V181 and V182 are reserved by the external-provider plan (P2-PH, P2-LAB).
-- Idempotent (IF NOT EXISTS). The duplicate removal deletes only extra TOTP
-- rows, which no lookup could ever have used (each one threw). No automated
-- rollback is declared.

ALTER TABLE security.user_mfa_enrollments
    ADD COLUMN IF NOT EXISTS pending_totp_secret VARCHAR(512);

ALTER TABLE security.mfa_backup_codes
    ADD COLUMN IF NOT EXISTS pending BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE security.user_mfa_enrollments
    ADD COLUMN IF NOT EXISTS last_totp_step BIGINT;

DELETE FROM security.user_mfa_enrollments e
USING (
    SELECT id,
           row_number() OVER (
               PARTITION BY user_id
               ORDER BY verified DESC, enabled DESC,
                        last_verified_at DESC NULLS LAST,
                        updated_at DESC, id
           ) AS rn
    FROM security.user_mfa_enrollments
    WHERE method = 'TOTP'
) d
WHERE e.id = d.id
  AND d.rn > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_user_mfa_enrollments_one_totp
    ON security.user_mfa_enrollments (user_id)
    WHERE method = 'TOTP';
