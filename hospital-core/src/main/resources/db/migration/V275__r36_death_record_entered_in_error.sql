-- V275: QA D1488 (round 36). A recorded death now refuses new care, so a death recorded on the wrong
-- patient would lock a living patient out, and V135 had no way back. The row is kept and marked entered
-- in error, with who, when and why. It leaves the register and the WHO indicators.
--
-- The unique index becomes partial: a person dies once, but a row entered in error is not a death, so the
-- patient can still have a real record later. Idempotent by rule.

ALTER TABLE clinical.death_records
    ADD COLUMN IF NOT EXISTS entered_in_error_at      TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS entered_in_error_by_id   UUID,
    ADD COLUMN IF NOT EXISTS entered_in_error_by_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS entered_in_error_reason  VARCHAR(500);

DROP INDEX IF EXISTS clinical.uq_death_record_patient;

CREATE UNIQUE INDEX IF NOT EXISTS uq_death_record_patient_live
    ON clinical.death_records (patient_id)
    WHERE entered_in_error_at IS NULL;
