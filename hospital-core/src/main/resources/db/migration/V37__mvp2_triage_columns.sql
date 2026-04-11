-- MVP 2: Triage & Rooming Workflow
-- Adds triage-related columns to encounters table

ALTER TABLE clinical.encounters
    ADD COLUMN esi_score          INTEGER,
    ADD COLUMN room_assignment    VARCHAR(100),
    ADD COLUMN triage_timestamp   TIMESTAMP,
    ADD COLUMN roomed_timestamp   TIMESTAMP;

-- Constraint: ESI score must be 1-5 when present
ALTER TABLE clinical.encounters
    ADD CONSTRAINT chk_encounters_esi_score
        CHECK (esi_score IS NULL OR (esi_score >= 1 AND esi_score <= 5));

-- Index for finding patients by room
CREATE INDEX idx_encounters_room_assignment
    ON clinical.encounters (room_assignment)
    WHERE room_assignment IS NOT NULL;

-- Index for triage timestamp queries
CREATE INDEX idx_encounters_triage_timestamp
    ON clinical.encounters (triage_timestamp)
    WHERE triage_timestamp IS NOT NULL;
