ALTER TABLE clinical.patients ADD COLUMN IF NOT EXISTS verified_sms_recipient TEXT;
ALTER TABLE clinical.patients ADD COLUMN IF NOT EXISTS verified_email_recipient TEXT;
ALTER TABLE platform.mail_outbox ADD COLUMN IF NOT EXISTS clinical_patient_id UUID;
ALTER TABLE platform.mail_outbox ADD COLUMN IF NOT EXISTS verified_patient_recipient TEXT;
ALTER TABLE platform.mail_outbox ADD COLUMN IF NOT EXISTS recipient_policy_version INTEGER NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS security.patient_recipient_challenges (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    patient_id UUID NOT NULL,
    channel VARCHAR(20) NOT NULL CHECK (channel IN ('IN_APP','EMAIL','SMS','PUSH')),
    recipient TEXT,
    code_hash VARCHAR(255),
    expires_at TIMESTAMP,
    attempts INTEGER NOT NULL DEFAULT 0,
    requested_by UUID NOT NULL,
    last_requested_at TIMESTAMP,
    window_started_at TIMESTAMP,
    request_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_patient_recipient_channel UNIQUE (patient_id, channel)
);
