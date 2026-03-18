-- =============================================================================
-- V29: Hotfix — add missing updated_at column to lab.instrument_outbox
-- BaseEntity requires both created_at and updated_at on every table.
-- =============================================================================
ALTER TABLE lab.instrument_outbox
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT now();
