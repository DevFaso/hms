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
-- users.email is; it is not a lookup key, so it is not indexed.
--
-- security.email_change_sends logs every mail this flow sends to a new
-- address (the code, or the in-use notice), so that one inbox cannot be
-- mailed past the per-address limit by several accounts, or by one account
-- re-targeting: the count is of sends, whatever each account's pending
-- change says now. It stores only a SHA-256 hash of the normalised address,
-- never the address itself, and rows older than the counting window are
-- purged as new ones are written.
--
-- Strictly additive: CREATE TABLE / CREATE INDEX IF NOT EXISTS only. created_at and
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

CREATE TABLE IF NOT EXISTS security.email_change_sends (
    id UUID PRIMARY KEY,
    address_hash VARCHAR(64) NOT NULL,
    sent_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_email_change_sends_address
    ON security.email_change_sends (address_hash, sent_at);
