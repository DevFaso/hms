-- V51: Add verification code fields to recovery contacts for email/phone verification

ALTER TABLE security.user_recovery_contacts
    ADD COLUMN IF NOT EXISTS verification_code_hash   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS verification_code_expires_at TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS verification_attempts     INT NOT NULL DEFAULT 0;

-- Reset any contacts that were auto-verified without proper verification
UPDATE security.user_recovery_contacts
SET verified = FALSE, verified_at = NULL
WHERE verified = TRUE AND verification_code_hash IS NULL;
