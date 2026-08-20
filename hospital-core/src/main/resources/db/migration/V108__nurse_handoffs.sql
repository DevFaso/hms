-- ─────────────────────────────────────────────────────────────────────────────
-- P0 #1: Nurse handoffs — real SBAR handoff records replacing the synthetic
-- rows NurseTaskServiceImpl used to fabricate ("entity arrives in MVP 2").
-- Mirrors the clinical.nursing_tasks shape (V21).
--
-- IF NOT EXISTS throughout: the first dev deployment created the table but
-- died before Liquibase recorded the changeset, so the redeploy re-ran it
-- and crashed on "relation nurse_handoffs already exists". Idempotent
-- statements let the re-run no-op and finally record the changeset.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS clinical.nurse_handoffs (
    id                UUID            NOT NULL DEFAULT gen_random_uuid(),
    patient_id        UUID            NOT NULL,
    hospital_id       UUID            NOT NULL,
    direction         VARCHAR(200)    NOT NULL,
    situation         TEXT,
    background        TEXT,
    assessment        TEXT,
    recommendation    TEXT,
    status            VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    created_by_name   VARCHAR(200),
    completed_by_name VARCHAR(200),
    completed_at      TIMESTAMP,
    created_at        TIMESTAMP       NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP       NOT NULL DEFAULT now(),

    CONSTRAINT pk_nurse_handoffs          PRIMARY KEY (id),
    CONSTRAINT fk_nurse_handoffs_patient  FOREIGN KEY (patient_id)  REFERENCES clinical.patients(id),
    CONSTRAINT fk_nurse_handoffs_hospital FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals(id)
);

CREATE INDEX IF NOT EXISTS idx_nurse_handoffs_patient  ON clinical.nurse_handoffs (patient_id);
CREATE INDEX IF NOT EXISTS idx_nurse_handoffs_hospital ON clinical.nurse_handoffs (hospital_id);
CREATE INDEX IF NOT EXISTS idx_nurse_handoffs_status   ON clinical.nurse_handoffs (status);
