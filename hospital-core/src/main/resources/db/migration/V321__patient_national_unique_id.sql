-- X8 / scorecard P1: optional protected national IU. V320 belongs to X2.
-- No backfill, checksum, required IU, or unique constraint on a patient's value/hash.
-- Alias owner UUID creates a private namespace: duplicate IUs on different patients
-- remain possible without changing legacy/public alias resolution or uniqueness.
-- Install the equivalent legacy uniqueness index BEFORE removing its blanket
-- constraint. Existing rows keep NULL owners and their existing protection.
-- Idempotent, forward-only; no automatic rollback after private aliases exist.
ALTER TABLE clinical.patients ADD COLUMN IF NOT EXISTS national_unique_id TEXT;
ALTER TABLE clinical.patients ADD COLUMN IF NOT EXISTS national_unique_id_hash VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_patient_national_unique_id_hash
    ON clinical.patients (national_unique_id_hash);
ALTER TABLE empi.identity_aliases ADD COLUMN IF NOT EXISTS national_unique_id_patient_id UUID;
CREATE UNIQUE INDEX IF NOT EXISTS uq_empi_public_alias_value
    ON empi.identity_aliases (alias_type, alias_value)
    WHERE national_unique_id_patient_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_empi_iu_alias_patient
    ON empi.identity_aliases (national_unique_id_patient_id);
ALTER TABLE empi.identity_aliases DROP CONSTRAINT IF EXISTS uq_empi_alias_value;
