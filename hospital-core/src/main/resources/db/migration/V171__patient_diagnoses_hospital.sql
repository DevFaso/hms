-- V171: clinical.patient_diagnoses gets the hospital it was recorded at.
--
-- WHY: V14 created the table with no hospital_id, so the patient-snapshot
-- drawer read it patient-wide -- unfiltered by the caller's readable set, not
-- testable by CrossHospitalRows.maySurface and not accounted in the reach.
-- Deriving a scope from diagnosed_by's hospital was rejected: that is the
-- subject's property, and a read's scope is the caller's.
--
-- WHAT THIS DOES: adds a NULLABLE hospital_id (FK to hospital.hospitals,
-- NO ACTION) and an index on (patient_id, hospital_id) for the scoped read.
-- NO BACKFILL, deliberately: nothing reliable says which hospital a pre-V171
-- row was recorded at. Those rows keep a NULL hospital, and the application
-- shows them to a staff reader only when that reader is a verified
-- super-admin (the JWT claim); the patient still sees their own on the
-- portal. PatientDiagnosis refuses to INSERT a row without a hospital, so
-- the NULL set only ever shrinks. (Nothing in the application writes this
-- table today; it is read-only legacy beside patient_problems.)
--
-- Additive and idempotent: ADD COLUMN IF NOT EXISTS, the FK guarded on
-- pg_constraint, CREATE INDEX IF NOT EXISTS. The FK is added VALID: every
-- existing value is NULL, so the check scans nothing it can fail on.
-- Rollback: DROP the column (the constraint and index go with it).

ALTER TABLE clinical.patient_diagnoses
    ADD COLUMN IF NOT EXISTS hospital_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint c
          JOIN pg_class      t ON t.oid = c.conrelid
          JOIN pg_namespace  n ON n.oid = t.relnamespace
         WHERE c.conname = 'fk_patient_diagnoses_hospital'
           AND n.nspname = 'clinical'
           AND t.relname = 'patient_diagnoses'
    ) THEN
        ALTER TABLE clinical.patient_diagnoses
            ADD CONSTRAINT fk_patient_diagnoses_hospital
            FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_patient_diagnoses_patient_hospital
    ON clinical.patient_diagnoses (patient_id, hospital_id);
