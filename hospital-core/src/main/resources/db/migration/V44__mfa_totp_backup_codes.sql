-- V44: MFA TOTP secret and backup codes for security hardening Phase 4

-- Add TOTP secret (encrypted at app level) and verified flag to existing enrollments table
ALTER TABLE security.user_mfa_enrollments
    ADD COLUMN IF NOT EXISTS totp_secret VARCHAR(512),
    ADD COLUMN IF NOT EXISTS verified BOOLEAN NOT NULL DEFAULT FALSE;

-- Backup codes table — single-use recovery codes
CREATE TABLE IF NOT EXISTS security.mfa_backup_codes (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL,
    code_hash   VARCHAR(255) NOT NULL,
    used        BOOLEAN     NOT NULL DEFAULT FALSE,
    used_at     TIMESTAMP WITHOUT TIME ZONE,
    created_at  TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_mfa_backup_codes_user_id
    ON security.mfa_backup_codes (user_id);
