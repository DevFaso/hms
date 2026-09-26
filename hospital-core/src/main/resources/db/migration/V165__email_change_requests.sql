-- =====================================================================
-- V165: a pending change of a user's own email address
--
-- POST /auth/me/change-email used to replace users.email at once, given
-- the current password. A typo, or somebody else's address, then became
-- the address password resets and notifications go to, and it blocked
-- that address's real owner from registering. The change now waits for
-- proof of ownership: a 6-digit code is sent to the NEW address and only
-- POST /auth/me/change-email/confirm with that code applies it. Until
-- then users.email is untouched and stays in force.
--
-- One row per user (uq_email_change_user), reused across requests:
--   * pending_email / code_hash / code_expires_at / code_attempts: the
--     change waiting for its code. The code is stored as a password-
--     encoder hash, never in clear, and dies after 15 minutes or 5 wrong
--     tries, as a recovery-contact code does.
--   * password_failures / password_window_started_at /
--     password_locked_until: wrong current passwords sent to this
--     endpoint. Deliberately NOT the login throttle: a stolen session
--     must not be able to lock the owner out of signing in by sending
--     five wrong passwords here. Persisted (not in memory) so the limit
--     holds across instances and restarts.
--
-- The address is stored as typed after normalisation (trim, lower case),
-- as users.email is; it is not a lookup key, so it is not indexed.
--
-- Strictly additive: CREATE ... IF NOT EXISTS only. created_at and
-- updated_at are NOT NULL because the entity extends BaseEntity (V153 /
-- V154). The FK cascades: an account that is hard-deleted takes its
-- pending change with it. No automated rollback is declared.
-- =====================================================================

CREATE TABLE IF NOT EXISTS security.email_change_requests (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    pending_email VARCHAR(100),
    code_hash VARCHAR(255),
    code_expires_at TIMESTAMP,
    code_attempts INTEGER NOT NULL DEFAULT 0,
    password_failures INTEGER NOT NULL DEFAULT 0,
    password_window_started_at TIMESTAMP,
    password_locked_until TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_email_change_user UNIQUE (user_id),
    CONSTRAINT fk_email_change_user FOREIGN KEY (user_id)
        REFERENCES security.users (id) ON DELETE CASCADE
);
