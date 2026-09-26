-- =====================================================================
-- V166: a pending, verified change of a user's own email address
--
-- Until now an account holder changed their own email through
-- PUT /users/{own id}, with no re-authentication at all: whoever held a
-- session could set any address, and the address took effect at once. It
-- is where a password reset is sent, so a stolen session could redirect it
-- and turn a short-lived token into a permanent takeover; a typo, or
-- somebody else's address, became the address of record and then blocked
-- that address's real owner from registering.
--
-- The self-service change now goes through POST /auth/me/change-email
-- (current password required; a 6-digit code is sent to the NEW address)
-- and POST /auth/me/change-email/confirm (that code applies it, and the
-- OLD address is told). Until the code comes back users.email is
-- untouched. This table holds the waiting change and the endpoint's own
-- counters, one row per user (uq_email_change_user), reused across
-- requests:
--   * pending_email, code_hash, code_expires_at, code_attempts: the
--     change waiting for its code. The code is a password-encoder hash,
--     never clear text, and dies after 15 minutes or 5 wrong tries.
--   * code_sent_at: when the pending address was mailed; the per-address
--     request limit counts other accounts' recent rows by it.
--   * request_count, request_window_started_at: requests that passed the
--     password check, per user, so the endpoint cannot mail an inbox in a
--     loop.
--   * password_failures, password_window_started_at,
--     password_locked_until: wrong current passwords sent to this
--     endpoint. Deliberately NOT the login throttle, so a stolen session
--     cannot lock the owner out of signing in. Persisted, so the limits
--     hold across instances and restarts.
--
-- pending_email is stored normalised (trimmed, lower case) as
-- users.email is. It is not indexed: the per-address count reads few rows,
-- and the table has at most one row per user.
--
-- Strictly additive: CREATE TABLE IF NOT EXISTS only. created_at and
-- updated_at are NOT NULL because the entity extends BaseEntity (the V153
-- lesson). The FK cascades, so a hard-deleted account takes its row with
-- it. No automated rollback is declared.
-- =====================================================================

CREATE TABLE IF NOT EXISTS security.email_change_requests (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    pending_email VARCHAR(100),
    code_hash VARCHAR(255),
    code_expires_at TIMESTAMP,
    code_attempts INTEGER NOT NULL DEFAULT 0,
    code_sent_at TIMESTAMP,
    request_count INTEGER NOT NULL DEFAULT 0,
    request_window_started_at TIMESTAMP,
    password_failures INTEGER NOT NULL DEFAULT 0,
    password_window_started_at TIMESTAMP,
    password_locked_until TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_email_change_user UNIQUE (user_id),
    CONSTRAINT fk_email_change_user FOREIGN KEY (user_id)
        REFERENCES security.users (id) ON DELETE CASCADE
);
