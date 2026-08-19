-- V67: P1 #9 — Break-the-glass emergency-access sessions
--
-- Records every short-lived emergency override granted to a clinician
-- so they can read a patient's chart without a pre-existing consent.
-- Every read served under a live session must emit a BREAK_GLASS_ACCESS
-- audit event (already present in support.audit_event_logs).
--
-- TTL defaults to 4 hours. Sessions can be revoked early by the
-- declaring user, by a hospital admin, or by SUPER_ADMIN. Once
-- revoked_at is set or expires_at has passed the session is no longer
-- a valid authorisation source.
--
-- Additive only; placed under clinical schema alongside patient_consents
-- because the data sits next to the consent record from a privacy /
-- retention standpoint.

CREATE TABLE IF NOT EXISTS clinical.break_glass_sessions (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL,
    patient_id          UUID NOT NULL,
    hospital_id         UUID NOT NULL,
    reason              VARCHAR(1024) NOT NULL,
    started_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    revoked_at          TIMESTAMP WITHOUT TIME ZONE,
    revoked_by_user_id  UUID,
    revoke_reason       VARCHAR(1024),
    audit_count         INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_bg_user        FOREIGN KEY (user_id)            REFERENCES security.users(id),
    CONSTRAINT fk_bg_patient     FOREIGN KEY (patient_id)         REFERENCES clinical.patients(id),
    CONSTRAINT fk_bg_hospital    FOREIGN KEY (hospital_id)        REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_bg_revoked_by  FOREIGN KEY (revoked_by_user_id) REFERENCES security.users(id),
    CONSTRAINT chk_bg_expiry_after_start CHECK (expires_at > started_at)
);

-- Hot-path lookup: find a live session for (user, patient).
-- Partial index on still-open sessions keeps it tight even after years
-- of historical rows accumulate.
CREATE INDEX IF NOT EXISTS idx_bg_sessions_user_patient_open
    ON clinical.break_glass_sessions (user_id, patient_id)
    WHERE revoked_at IS NULL;

-- Patient-level view for the "any active emergency access?" banner and
-- for compliance reviews.
CREATE INDEX IF NOT EXISTS idx_bg_sessions_patient_open
    ON clinical.break_glass_sessions (patient_id, expires_at DESC)
    WHERE revoked_at IS NULL;

-- Per-hospital audit/review queries (admins reviewing their own facility).
CREATE INDEX IF NOT EXISTS idx_bg_sessions_hospital_started
    ON clinical.break_glass_sessions (hospital_id, started_at DESC);
