-- =============================================================================
-- V31: Patient Portal — pre-visit questionnaire tables
--
-- New tables:
--   clinical.pre_visit_questionnaires  (questionnaire templates)
--   clinical.questionnaire_responses   (patient answers)
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. clinical.pre_visit_questionnaires
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS clinical.pre_visit_questionnaires (
    id              UUID          NOT NULL PRIMARY KEY,
    hospital_id     UUID          NOT NULL,
    department_id   UUID,
    title           VARCHAR(255)  NOT NULL,
    description     VARCHAR(1000),
    questions_json  TEXT          NOT NULL,
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pvq_hospital
    ON clinical.pre_visit_questionnaires (hospital_id);

CREATE INDEX IF NOT EXISTS idx_pvq_department
    ON clinical.pre_visit_questionnaires (department_id);

CREATE INDEX IF NOT EXISTS idx_pvq_active
    ON clinical.pre_visit_questionnaires (active);

-- ---------------------------------------------------------------------------
-- 2. clinical.questionnaire_responses
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS clinical.questionnaire_responses (
    id                   UUID         NOT NULL PRIMARY KEY,
    patient_id           UUID         NOT NULL,
    hospital_id          UUID         NOT NULL,
    questionnaire_id     UUID         NOT NULL,
    appointment_id       UUID,
    answers_json         TEXT         NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'SUBMITTED',
    submitted_at         TIMESTAMP,
    questionnaire_title  VARCHAR(255) NOT NULL,
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_qr_patient
    ON clinical.questionnaire_responses (patient_id);

CREATE INDEX IF NOT EXISTS idx_qr_questionnaire
    ON clinical.questionnaire_responses (questionnaire_id);

CREATE INDEX IF NOT EXISTS idx_qr_appointment
    ON clinical.questionnaire_responses (appointment_id);

CREATE INDEX IF NOT EXISTS idx_qr_status
    ON clinical.questionnaire_responses (status);
