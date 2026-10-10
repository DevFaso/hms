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
-- V181 and V182 are reserved by the external-provider plan (P2-PH, P2-LAB).
-- Strictly additive, idempotent (ADD COLUMN IF NOT EXISTS). No automated
-- rollback is declared: both columns are inert until the application uses them.

ALTER TABLE security.user_mfa_enrollments
    ADD COLUMN IF NOT EXISTS pending_totp_secret VARCHAR(512);

ALTER TABLE security.mfa_backup_codes
    ADD COLUMN IF NOT EXISTS pending BOOLEAN NOT NULL DEFAULT FALSE;
