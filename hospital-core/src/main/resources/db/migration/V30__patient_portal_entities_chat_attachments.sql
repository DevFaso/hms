-- =============================================================================
-- V30: Patient Portal — new clinical tables + chat message attachment columns
--
-- New tables:
--   clinical.health_maintenance_reminders  (Feature 19 — Health Reminders)
--   clinical.treatment_progress_entries    (Feature 20 — Treatment Progress)
--   clinical.patient_reported_outcomes     (Feature 21 — Patient Reported Outcomes)
--
-- Altered tables:
--   support.chat_messages                  (Feature 22 — Message Attachments)
--     + attachment_url, attachment_name, attachment_content_type, attachment_size_bytes
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. clinical.health_maintenance_reminders
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS clinical.health_maintenance_reminders (
    id                  UUID         NOT NULL PRIMARY KEY,
    patient_id          UUID         NOT NULL,
    hospital_id         UUID         NOT NULL,
    type                VARCHAR(40)  NOT NULL,
    due_date            DATE,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    notes               VARCHAR(1000),
    completed_date      DATE,
    completed_by        VARCHAR(200),
    created_by_user_id  UUID,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_hmr_patient
    ON clinical.health_maintenance_reminders (patient_id);

CREATE INDEX IF NOT EXISTS idx_hmr_hospital
    ON clinical.health_maintenance_reminders (hospital_id);

CREATE INDEX IF NOT EXISTS idx_hmr_due_date
    ON clinical.health_maintenance_reminders (due_date);

CREATE INDEX IF NOT EXISTS idx_hmr_status
    ON clinical.health_maintenance_reminders (status);

-- ---------------------------------------------------------------------------
-- 2. clinical.treatment_progress_entries
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS clinical.treatment_progress_entries (
    id                  UUID      NOT NULL PRIMARY KEY,
    treatment_plan_id   UUID      NOT NULL,
    patient_id          UUID      NOT NULL,
    progress_date       DATE      NOT NULL,
    progress_note       TEXT,
    self_rating         INTEGER   CHECK (self_rating BETWEEN 1 AND 10),
    on_track            BOOLEAN   DEFAULT TRUE,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tpe_plan
    ON clinical.treatment_progress_entries (treatment_plan_id);

CREATE INDEX IF NOT EXISTS idx_tpe_patient
    ON clinical.treatment_progress_entries (patient_id);

-- ---------------------------------------------------------------------------
-- 3. clinical.patient_reported_outcomes
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS clinical.patient_reported_outcomes (
    id           UUID        NOT NULL PRIMARY KEY,
    patient_id   UUID        NOT NULL,
    hospital_id  UUID        NOT NULL,
    outcome_type VARCHAR(40) NOT NULL,
    score        INTEGER     NOT NULL CHECK (score BETWEEN 0 AND 10),
    notes        TEXT,
    report_date  DATE        NOT NULL,
    encounter_id UUID,
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pro_patient
    ON clinical.patient_reported_outcomes (patient_id);

CREATE INDEX IF NOT EXISTS idx_pro_hospital
    ON clinical.patient_reported_outcomes (hospital_id);

CREATE INDEX IF NOT EXISTS idx_pro_type
    ON clinical.patient_reported_outcomes (outcome_type);

CREATE INDEX IF NOT EXISTS idx_pro_date
    ON clinical.patient_reported_outcomes (report_date);

-- ---------------------------------------------------------------------------
-- 4. support.chat_messages — add attachment columns
-- ---------------------------------------------------------------------------
ALTER TABLE support.chat_messages
    ADD COLUMN IF NOT EXISTS attachment_url          VARCHAR(512),
    ADD COLUMN IF NOT EXISTS attachment_name         VARCHAR(255),
    ADD COLUMN IF NOT EXISTS attachment_content_type VARCHAR(120),
    ADD COLUMN IF NOT EXISTS attachment_size_bytes   BIGINT;
