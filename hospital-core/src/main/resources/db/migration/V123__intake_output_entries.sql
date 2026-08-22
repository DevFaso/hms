-- V123: clinical.intake_output_entries (P3 item 18, flowsheets/I&O).
--
-- WHY: intake/output existed only as a free-text NursingTask category string —
-- completing an "INTAKE_OUTPUT" task stored a completion note and NO volumes,
-- so fluid balance was uncomputable anywhere in the system. This is the first
-- numeric I&O surface. ("Nursing Intake Flowsheet" in MVP 3 is an admission
-- assessment despite its name; it stores no fluid data.)
--
-- Follows the observation-row house pattern (labor_partograph_entries /
-- postpartum_observations): observation-time/late-entry quad + per-row
-- tenancy and attribution. category is derived from route in the entity but
-- persisted so totals stay a plain GROUP BY.
--
-- New table, so it carries REAL foreign keys. Idempotent by rule (a deploy
-- killed mid-Liquibase must not wedge the environment).

CREATE TABLE IF NOT EXISTS clinical.intake_output_entries (
    id                    UUID         NOT NULL DEFAULT gen_random_uuid(),
    patient_id            UUID         NOT NULL,
    hospital_id           UUID         NOT NULL,
    recorded_by_staff_id  UUID,
    documented_by_user_id UUID,
    observation_time      TIMESTAMP    NOT NULL,
    documented_at         TIMESTAMP    NOT NULL DEFAULT now(),
    late_entry            BOOLEAN      NOT NULL DEFAULT false,
    original_entry_time   TIMESTAMP,
    category              VARCHAR(10)  NOT NULL,
    route                 VARCHAR(20)  NOT NULL,
    volume_ml             INTEGER      NOT NULL,
    notes                 VARCHAR(500),
    created_at            TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_intake_output_entries      PRIMARY KEY (id),
    CONSTRAINT fk_io_entry_patient           FOREIGN KEY (patient_id)            REFERENCES clinical.patients(id),
    CONSTRAINT fk_io_entry_hospital          FOREIGN KEY (hospital_id)           REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_io_entry_staff             FOREIGN KEY (recorded_by_staff_id)  REFERENCES hospital.staff(id) ON DELETE NO ACTION,
    CONSTRAINT fk_io_entry_user              FOREIGN KEY (documented_by_user_id) REFERENCES security.users(id) ON DELETE NO ACTION,
    CONSTRAINT ck_io_entry_volume_positive   CHECK (volume_ml > 0)
);

CREATE INDEX IF NOT EXISTS idx_io_entry_patient  ON clinical.intake_output_entries (patient_id);
CREATE INDEX IF NOT EXISTS idx_io_entry_hospital ON clinical.intake_output_entries (hospital_id);
CREATE INDEX IF NOT EXISTS idx_io_entry_time     ON clinical.intake_output_entries (observation_time);
