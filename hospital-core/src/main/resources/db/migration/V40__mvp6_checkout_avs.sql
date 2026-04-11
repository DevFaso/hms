-- =====================================================================
-- V40: MVP 6 — Check-Out & After-Visit Summary (AVS)
-- Adds checkout fields to encounters table.
-- =====================================================================

-- 1. Add checkout_timestamp to encounters
ALTER TABLE clinical.encounters
    ADD COLUMN IF NOT EXISTS checkout_timestamp TIMESTAMP;

-- 2. Add follow-up instructions (free-text, may be lengthy)
ALTER TABLE clinical.encounters
    ADD COLUMN IF NOT EXISTS follow_up_instructions TEXT;

-- 3. Add discharge diagnoses (stored as JSON array of strings)
ALTER TABLE clinical.encounters
    ADD COLUMN IF NOT EXISTS discharge_diagnoses TEXT;

-- 4. Index on checkout_timestamp for AVS lookups
CREATE INDEX IF NOT EXISTS idx_encounter_checkout_ts
    ON clinical.encounters (checkout_timestamp)
    WHERE checkout_timestamp IS NOT NULL;
