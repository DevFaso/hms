-- V43: Password history table for security hardening (T-18)
-- Stores hashed passwords to prevent reuse of the last 5 passwords.

CREATE TABLE IF NOT EXISTS security.password_history (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES security.users(id) ON DELETE CASCADE,
    password_hash   VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_password_history_user_id ON security.password_history(user_id);
