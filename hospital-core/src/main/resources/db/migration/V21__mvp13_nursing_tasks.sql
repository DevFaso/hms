-- ─────────────────────────────────────────────────────────────────────────────
-- MVP 13: Nurse Station Cockpit — Phase 3  (Nursing Task Board)
-- Creates the clinical.nursing_tasks table that backs the bedside task board.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE clinical.nursing_tasks (
    id               UUID            NOT NULL DEFAULT gen_random_uuid(),
    patient_id       UUID            NOT NULL,
    hospital_id      UUID            NOT NULL,
    category         VARCHAR(40)     NOT NULL,
    description      TEXT,
    priority         VARCHAR(20)     NOT NULL DEFAULT 'ROUTINE',
    status           VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    due_at           TIMESTAMP,
    completed_at     TIMESTAMP,
    completed_by_name VARCHAR(200),
    completion_note  TEXT,
    created_by_name  VARCHAR(200),
    created_at       TIMESTAMP       NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP       NOT NULL DEFAULT now(),

    CONSTRAINT pk_nursing_tasks        PRIMARY KEY (id),
    CONSTRAINT fk_nursing_tasks_patient  FOREIGN KEY (patient_id)  REFERENCES clinical.patients(id),
    CONSTRAINT fk_nursing_tasks_hospital FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals(id)
);

CREATE INDEX idx_nursing_tasks_patient  ON clinical.nursing_tasks (patient_id);
CREATE INDEX idx_nursing_tasks_hospital ON clinical.nursing_tasks (hospital_id);
CREATE INDEX idx_nursing_tasks_status   ON clinical.nursing_tasks (status);
CREATE INDEX idx_nursing_tasks_due_at   ON clinical.nursing_tasks (due_at);
