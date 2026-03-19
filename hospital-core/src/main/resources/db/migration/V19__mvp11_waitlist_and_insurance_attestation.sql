-- ─────────────────────────────────────────────────────────────────────────────
-- MVP 11: Waitlist table + insurance eligibility-attestation columns
-- ─────────────────────────────────────────────────────────────────────────────

-- ── 0. Create scheduling schema ──────────────────────────────────────────────
CREATE SCHEMA IF NOT EXISTS scheduling;

-- ── 1. Appointment waitlist ──────────────────────────────────────────────────
CREATE TABLE scheduling.appointment_waitlist (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id          UUID         NOT NULL REFERENCES hospital.hospitals(id),
    department_id        UUID         NOT NULL REFERENCES hospital.departments(id),
    patient_id           UUID         NOT NULL REFERENCES clinical.patients(id),
    preferred_provider_id UUID        REFERENCES hospital.staff(id),
    requested_date_from  DATE,
    requested_date_to    DATE,
    priority             VARCHAR(20)  NOT NULL DEFAULT 'ROUTINE',
    reason               TEXT,
    status               VARCHAR(20)  NOT NULL DEFAULT 'WAITING',
    offered_appointment_id UUID       REFERENCES clinical.appointments(id),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by           VARCHAR(255)
);

CREATE INDEX idx_waitlist_hospital   ON scheduling.appointment_waitlist(hospital_id);
CREATE INDEX idx_waitlist_department ON scheduling.appointment_waitlist(department_id);
CREATE INDEX idx_waitlist_patient    ON scheduling.appointment_waitlist(patient_id);
CREATE INDEX idx_waitlist_status     ON scheduling.appointment_waitlist(status);

-- ── 2. Insurance eligibility attestation columns ─────────────────────────────
ALTER TABLE clinical.patient_insurances
    ADD COLUMN IF NOT EXISTS verified_at        TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS verified_by        VARCHAR(255),
    ADD COLUMN IF NOT EXISTS eligibility_notes  TEXT;
