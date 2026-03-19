-- V17: Add assignment tracking fields to clinical.consultations
-- Tracks when a consultation was assigned and who performed the assignment

ALTER TABLE clinical.consultations
    ADD COLUMN IF NOT EXISTS assigned_at   TIMESTAMP,
    ADD COLUMN IF NOT EXISTS assigned_by_id UUID;
