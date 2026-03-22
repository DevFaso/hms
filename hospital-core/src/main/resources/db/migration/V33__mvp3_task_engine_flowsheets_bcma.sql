-- ============================================================
-- V33: MVP3 — Task engine (SLA/escalation), flowsheets, BCMA
-- ============================================================

-- ── 1. Extend nursing_tasks with SLA / assignment / focus ──

ALTER TABLE clinical.nursing_tasks
    ADD COLUMN IF NOT EXISTS source          VARCHAR(20)  DEFAULT 'MANUAL',
    ADD COLUMN IF NOT EXISTS assigned_to_staff_id UUID,
    ADD COLUMN IF NOT EXISTS sla_deadline    TIMESTAMP,
    ADD COLUMN IF NOT EXISTS escalated_at    TIMESTAMP,
    ADD COLUMN IF NOT EXISTS escalation_level INT         DEFAULT 0,
    ADD COLUMN IF NOT EXISTS focus_type      VARCHAR(40),
    ADD COLUMN IF NOT EXISTS focus_id        UUID;

ALTER TABLE clinical.nursing_tasks
    ADD CONSTRAINT fk_nursing_tasks_assigned_staff
        FOREIGN KEY (assigned_to_staff_id) REFERENCES hospital.staff(id);

CREATE INDEX IF NOT EXISTS idx_nursing_tasks_assigned
    ON clinical.nursing_tasks(assigned_to_staff_id);
CREATE INDEX IF NOT EXISTS idx_nursing_tasks_sla
    ON clinical.nursing_tasks(sla_deadline);
CREATE INDEX IF NOT EXISTS idx_nursing_tasks_focus
    ON clinical.nursing_tasks(focus_type, focus_id);

-- ── 2. Flowsheet entries table ──────────────────────────────

CREATE TABLE IF NOT EXISTS clinical.flowsheet_entries (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id       UUID         NOT NULL REFERENCES clinical.patients(id),
    hospital_id      UUID         NOT NULL REFERENCES hospital.hospitals(id),
    type             VARCHAR(30)  NOT NULL,
    numeric_value    DOUBLE PRECISION,
    unit             VARCHAR(20),
    text_value       VARCHAR(2000),
    sub_type         VARCHAR(40),
    recorded_at      TIMESTAMP    NOT NULL,
    recorded_by_name VARCHAR(200),
    notes            VARCHAR(1000),
    created_at       TIMESTAMP    DEFAULT now(),
    updated_at       TIMESTAMP    DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_flowsheet_patient
    ON clinical.flowsheet_entries(patient_id);
CREATE INDEX IF NOT EXISTS idx_flowsheet_hospital
    ON clinical.flowsheet_entries(hospital_id);
CREATE INDEX IF NOT EXISTS idx_flowsheet_type
    ON clinical.flowsheet_entries(type);
CREATE INDEX IF NOT EXISTS idx_flowsheet_recorded
    ON clinical.flowsheet_entries(recorded_at);

-- ── 3. BCMA fields on medication_administration_records ─────

ALTER TABLE clinical.medication_administration_records
    ADD COLUMN IF NOT EXISTS patient_barcode   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS med_barcode       VARCHAR(255),
    ADD COLUMN IF NOT EXISTS scan_device_id    VARCHAR(100),
    ADD COLUMN IF NOT EXISTS scan_verified     BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS scan_override     BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS scan_override_reason VARCHAR(500);
