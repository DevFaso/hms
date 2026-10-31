-- V252 (round 19, fix/r19-lab-results-chart). Plain statements, no DO block.
-- Idempotent.
--
-- 1. QA D1120: a lab test definition is versioned the way a laboratory
--    versions a procedure. An ACTIVE definition is never edited in place (its
--    ranges are locked, D650); "new version" copies it into a DRAFT linked to
--    it (supersedes_definition_id), which goes through the same four-eyes
--    approval and, on ACTIVATE, retires the version it replaces. Until now the
--    only way to change a live range was RETIRE plus a new, unlinked
--    definition with the same code, and a duplicate code was accepted
--    silently. Every existing definition is version 1 and replaces nothing.
ALTER TABLE lab.lab_test_definitions
    ADD COLUMN IF NOT EXISTS supersedes_definition_id UUID;
ALTER TABLE lab.lab_test_definitions
    ADD COLUMN IF NOT EXISTS version_number INTEGER NOT NULL DEFAULT 1;
CREATE INDEX IF NOT EXISTS idx_lab_testdef_supersedes
    ON lab.lab_test_definitions (supersedes_definition_id);

-- 2. QA D1107: the nurse-station care-note dialog saved every note unsigned,
--    so doctors read each one as "Brouillon". The dialog has no draft state:
--    the nurse saves and attests the note, which is final. Notes written by
--    that path (and only those) are recognised by what it never set - no
--    author staff row, no credentials, no readability score, no event time,
--    not a late entry - with the accuracy attestation it always set. The
--    signature is the author's, at the time the note was documented. Notes
--    from the full nursing-note form keep their own signature state.
UPDATE clinical.nursing_notes
   SET signed_at = documented_at,
       signed_by_name = author_name
 WHERE signed_at IS NULL
   AND author_staff_id IS NULL
   AND author_credentials IS NULL
   AND readability_score IS NULL
   AND event_occurred_at IS NULL
   AND late_entry = FALSE
   AND attest_accuracy = TRUE
   AND attest_spell_check = FALSE
   AND attest_no_abbreviations = FALSE;
