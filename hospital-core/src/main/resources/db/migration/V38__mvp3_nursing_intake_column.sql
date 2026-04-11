-- =========================================================================
-- V38: MVP 3 — Nursing Intake Flowsheet
-- Adds nursing_intake_timestamp to clinical.encounters
-- =========================================================================

ALTER TABLE clinical.encounters
    ADD COLUMN IF NOT EXISTS nursing_intake_timestamp TIMESTAMP;

-- Partial index for encounters that have completed nursing intake
CREATE INDEX IF NOT EXISTS idx_encounters_nursing_intake
    ON clinical.encounters (nursing_intake_timestamp)
    WHERE nursing_intake_timestamp IS NOT NULL;
