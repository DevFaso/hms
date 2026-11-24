-- V366: a password a person did not choose (generated at registration or activation, or typed by an
-- administrator for someone else) is kept as a hash only and stops working 72 hours after it was issued.
-- Additive, plain statements, no DO block, idempotent.

-- 1. When the account's temporary password stops working. NULL: a password the holder chose.
ALTER TABLE security.users ADD COLUMN IF NOT EXISTS temp_password_expires_at TIMESTAMP WITHOUT TIME ZONE;

-- 2. No clear-text copy of any password is kept on an assignment. The account keeps its hashed password; an
--    account that was never used is issued a fresh one when its activation code is confirmed. The column is no
--    longer mapped by the application; V367 keeps it empty.
UPDATE security.user_role_hospital_assignment
   SET temp_plain_password = NULL
 WHERE temp_plain_password IS NOT NULL;

-- 3. Accounts still on a password someone else issued them (a forced change not done yet) have 72 hours from
--    this release to replace it; after that the holder asks for a reset link. Left out: a forced change that is
--    the 90-day rotation of the holder's own password (forced at least 89 days after that password was set),
--    which must still be changed but does not expire. Only rows not stamped yet are touched.
UPDATE security.users
   SET temp_password_expires_at = LOCALTIMESTAMP + INTERVAL '72 hours'
 WHERE force_password_change = TRUE
   AND temp_password_expires_at IS NULL
   AND NOT (password_rotation_forced_at IS NOT NULL
            AND password_changed_at IS NOT NULL
            AND password_rotation_forced_at >= password_changed_at + INTERVAL '89 days');
