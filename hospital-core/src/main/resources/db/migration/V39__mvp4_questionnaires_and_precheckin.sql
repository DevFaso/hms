-- =========================================================================
-- V39: MVP 4 — Pre-Visit Questionnaires & Pre-Check-In
-- 1) clinical.questionnaires             — questionnaire templates
-- 2) clinical.questionnaire_responses    — patient-submitted answers
-- 3) ALTER clinical.appointments         — pre_checked_in, pre_checkin_timestamp
-- =========================================================================

-- 1) Questionnaire templates (admin-managed)
CREATE TABLE IF NOT EXISTS clinical.questionnaires (
    id              UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
    title           VARCHAR(255)    NOT NULL,
    description     VARCHAR(1024),
    questions       JSONB           NOT NULL DEFAULT '[]'::jsonb,
    version         INT             NOT NULL DEFAULT 1,
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    department_id   UUID            REFERENCES hospital.departments(id),
    hospital_id     UUID            NOT NULL REFERENCES hospital.hospitals(id),
    created_at      TIMESTAMP       NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_questionnaire_hospital
    ON clinical.questionnaires (hospital_id);
CREATE INDEX IF NOT EXISTS idx_questionnaire_dept
    ON clinical.questionnaires (department_id) WHERE department_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_questionnaire_active
    ON clinical.questionnaires (active) WHERE active = TRUE;

-- 2) Questionnaire responses (patient-submitted)
CREATE TABLE IF NOT EXISTS clinical.questionnaire_responses (
    id                  UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
    questionnaire_id    UUID        NOT NULL REFERENCES clinical.questionnaires(id),
    patient_id          UUID        NOT NULL REFERENCES clinical.patients(id),
    appointment_id      UUID        NOT NULL REFERENCES clinical.appointments(id),
    responses           JSONB       NOT NULL DEFAULT '{}'::jsonb,
    submitted_at        TIMESTAMP   NOT NULL DEFAULT now(),
    created_at          TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_qr_patient
    ON clinical.questionnaire_responses (patient_id);
CREATE INDEX IF NOT EXISTS idx_qr_appointment
    ON clinical.questionnaire_responses (appointment_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_qr_questionnaire_appointment
    ON clinical.questionnaire_responses (questionnaire_id, appointment_id);

-- 3) Pre-check-in columns on appointments
ALTER TABLE clinical.appointments
    ADD COLUMN IF NOT EXISTS pre_checked_in          BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS pre_checkin_timestamp    TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_appt_precheckin
    ON clinical.appointments (pre_checked_in) WHERE pre_checked_in = TRUE;
