-- V245 (round 17, clinical workflow).
--
-- QA D922 (decision 2026-10-28): a controlled drug given from the station MAR
-- or the eMAR is witnessed by a second clinician of the same hospital, not
-- the one who gives it, as real hospitals require. The witness is recorded
-- with the dose. Nullable: only a GIVEN dose of a controlled drug carries
-- one, and every existing row predates the rule.
--
-- QA D1044: the title a nurse gives a care note was dropped; it is kept.
--
-- Schema only, no data change, no PHI (a user id; a note title is clinical
-- text like the note's narrative beside it). Strictly additive, plain
-- statements, no DO block. Idempotent (ADD COLUMN IF NOT EXISTS skips the
-- inline foreign key with the column). No automated rollback is declared.

ALTER TABLE clinical.medication_administration_records
    ADD COLUMN IF NOT EXISTS witness_user_id UUID
        CONSTRAINT fk_mar_witness REFERENCES security.users (id);

ALTER TABLE clinical.nursing_notes
    ADD COLUMN IF NOT EXISTS title VARCHAR(255);
