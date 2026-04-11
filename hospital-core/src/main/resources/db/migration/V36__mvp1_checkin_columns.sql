-- MVP 1: Patient Check-In & Arrival Workflow
-- Adds columns to support the check-in action on appointments and encounters.

-- Appointment: timestamp when patient was checked in
ALTER TABLE clinical.appointments
    ADD COLUMN IF NOT EXISTS checked_in_at TIMESTAMP;

-- Encounter: timestamp when patient physically arrived
ALTER TABLE clinical.encounters
    ADD COLUMN IF NOT EXISTS arrival_timestamp TIMESTAMP;

-- Encounter: chief complaint captured at check-in / triage
ALTER TABLE clinical.encounters
    ADD COLUMN IF NOT EXISTS chief_complaint VARCHAR(2048);
