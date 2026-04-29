-- V60: P1 #1 — Terminology binding (LOINC on lab test definitions)
--
-- Adds optional LOINC code and human-readable display to
-- lab.lab_test_definitions so lab orders/results can project as FHIR
-- Observations carrying a LOINC coding (per WHO SMART / OpenHIE
-- profiles), instead of the current local urn:hms:lab:test-code system.
--
-- Additive only. Existing rows unchanged. Format is enforced at the
-- application layer via TerminologyCodes#isValidLoinc; the columns are
-- nullable so freetext lab definitions keep working until they are
-- re-coded.
--
-- ATC + RxNorm columns on clinical.medication_catalog_items already
-- exist (V1 + V43); ICD version on clinical.patient_problems already
-- exists (V1). This migration only fills the LOINC gap.

ALTER TABLE lab.lab_test_definitions
    ADD COLUMN IF NOT EXISTS loinc_code VARCHAR(20),
    ADD COLUMN IF NOT EXISTS loinc_display VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_lab_testdef_loinc
    ON lab.lab_test_definitions (loinc_code)
    WHERE loinc_code IS NOT NULL;
