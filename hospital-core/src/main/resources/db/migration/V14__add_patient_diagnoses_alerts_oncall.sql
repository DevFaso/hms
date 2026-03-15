-- V14: Add patient_diagnoses, clinical_alerts, and on_call_schedule tables
-- Supports: active diagnosis tracking, clinical alert system, on-call scheduling

-- ============================================================
-- 1. Patient Diagnoses (clinical schema)
-- ============================================================
CREATE TABLE IF NOT EXISTS clinical.patient_diagnoses (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id      UUID NOT NULL REFERENCES clinical.patients(id) ON DELETE CASCADE,
    diagnosed_by    UUID REFERENCES hospital.staff(id) ON DELETE SET NULL,
    icd_code        VARCHAR(20),
    description     VARCHAR(500) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    diagnosed_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_patient_diagnoses_patient
    ON clinical.patient_diagnoses(patient_id);

CREATE INDEX IF NOT EXISTS idx_patient_diagnoses_patient_status
    ON clinical.patient_diagnoses(patient_id, status);

-- ============================================================
-- 2. Clinical Alerts (clinical schema)
-- ============================================================
CREATE TABLE IF NOT EXISTS clinical.clinical_alerts (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id       UUID REFERENCES clinical.patients(id) ON DELETE SET NULL,
    staff_id         UUID REFERENCES hospital.staff(id) ON DELETE SET NULL,
    alert_type       VARCHAR(50)  NOT NULL,
    severity         VARCHAR(20)  NOT NULL DEFAULT 'NORMAL',
    message          TEXT         NOT NULL,
    acknowledged     BOOLEAN      NOT NULL DEFAULT false,
    acknowledged_at  TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_clinical_alerts_patient
    ON clinical.clinical_alerts(patient_id);

CREATE INDEX IF NOT EXISTS idx_clinical_alerts_staff
    ON clinical.clinical_alerts(staff_id);

CREATE INDEX IF NOT EXISTS idx_clinical_alerts_unacked
    ON clinical.clinical_alerts(staff_id, acknowledged)
    WHERE acknowledged = false;

-- ============================================================
-- 3. On-Call Schedule (hospital schema)
-- ============================================================
CREATE TABLE IF NOT EXISTS hospital.on_call_schedule (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_id       UUID NOT NULL REFERENCES hospital.staff(id) ON DELETE CASCADE,
    department_id  UUID REFERENCES hospital.departments(id) ON DELETE SET NULL,
    start_time     TIMESTAMPTZ NOT NULL,
    end_time       TIMESTAMPTZ NOT NULL,
    notes          VARCHAR(500),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_on_call_time CHECK (end_time > start_time)
);

CREATE INDEX IF NOT EXISTS idx_on_call_staff
    ON hospital.on_call_schedule(staff_id);

CREATE INDEX IF NOT EXISTS idx_on_call_time_range
    ON hospital.on_call_schedule(start_time, end_time);
