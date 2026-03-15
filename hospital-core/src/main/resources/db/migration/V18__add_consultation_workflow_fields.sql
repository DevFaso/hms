-- V18: Add workflow progress fields to clinical.consultations
-- Tracks when a consultation was started and when/why it was declined

ALTER TABLE clinical.consultations
    ADD COLUMN IF NOT EXISTS started_at    TIMESTAMP,
    ADD COLUMN IF NOT EXISTS declined_at   TIMESTAMP,
    ADD COLUMN IF NOT EXISTS decline_reason TEXT;
