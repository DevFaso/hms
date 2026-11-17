-- V300: the free-text narratives of the death family are encrypted at rest.
--
-- DeathDispute.reason / resolutionReason, DeathRecord's causes, amendment,
-- notes and entered-in-error reason, and FetalDeathEvent's creation and
-- supersede reasons now carry @Convert(EncryptedStringConverter). Their
-- ciphertext (gcm1: + Base64(iv || ciphertext + tag)) of a 1000-character
-- accented value is far longer than the VARCHAR it was sized for, so every
-- column becomes TEXT. The input limits stay on the entities and DTOs (@Size),
-- so users see the same limits as before.
--
-- Existing rows are NOT rewritten here: the converter reads a value without the
-- gcm1: prefix verbatim, and PhiTextEncryptionBackfill encrypts legacy values
-- at startup, in Java, with the configured key.
--
-- Idempotent: ALTER ... TYPE TEXT on a column that is already TEXT is a no-op.
-- No view, index or constraint reads these columns (checked against every
-- earlier migration). V298 and V295 are reserved by other branches.
-- Rollback: none declared (forward-only; widening loses nothing).

ALTER TABLE clinical.death_disputes
    ALTER COLUMN reason            TYPE TEXT,
    ALTER COLUMN resolution_reason TYPE TEXT;

ALTER TABLE clinical.death_records
    ALTER COLUMN immediate_cause         TYPE TEXT,
    ALTER COLUMN underlying_cause        TYPE TEXT,
    ALTER COLUMN contributing_causes     TYPE TEXT,
    ALTER COLUMN amendment_reason        TYPE TEXT,
    ALTER COLUMN notes                   TYPE TEXT,
    ALTER COLUMN entered_in_error_reason TYPE TEXT;

ALTER TABLE clinical.fetal_death_events
    ALTER COLUMN creation_reason  TYPE TEXT,
    ALTER COLUMN supersede_reason TYPE TEXT;
