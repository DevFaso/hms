-- V238 (round 14, clinical/lab wave).
--
-- QA D831: an encounter holds one note row per entry. An inpatient stay keeps
-- one entry per progress note and author, so a second clinician's entry is a
-- new row and never replaces the first clinician's unsigned draft. The
-- Liquibase-built schema never carried the per-encounter unique key the entity
-- used to declare, but a database whose table Hibernate once generated would
-- have it; it is dropped where present. The encounter lookup gets its index.
--
-- QA D834: a qualitative lab test (a malaria rapid test) has no numeric range.
-- Its definition records the expected (normal) result instead, for example
-- "Négatif", which lets it go through review and activation without a fake
-- 0-0 range, and is what the patient sees beside the result.
--
-- Schema only, no data change, no PHI. Plain statements, no DO block.
-- Idempotent.

ALTER TABLE clinical.encounter_notes
    DROP CONSTRAINT IF EXISTS uk_encounter_note_encounter;

DROP INDEX IF EXISTS clinical.uk_encounter_note_encounter;

CREATE INDEX IF NOT EXISTS idx_encounter_notes_encounter
    ON clinical.encounter_notes (encounter_id);

ALTER TABLE lab.lab_test_definitions
    ADD COLUMN IF NOT EXISTS expected_result VARCHAR(255);
