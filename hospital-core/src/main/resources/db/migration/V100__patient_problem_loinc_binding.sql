-- =====================================================================
-- V100: LOINC binding columns on clinical.patient_problems
-- (roadmap row 26, v1.1 / Clinical Safety / "CDS Hooks LOINC binding")
--
-- The hms-patient-view CDS Hook today emits problem cards keyed on
-- ICD-10 (problem_code + icd_version). For Cerner / Epic / SMART-on-FHIR
-- consumers that expect a LOINC alongside the ICD coding — typically as
-- a hint for the most clinically-relevant observation panel for a given
-- problem (HbA1c for diabetes, FEV1/FVC for asthma, blood-pressure
-- panel for hypertension) — we add an optional LOINC pair on every
-- problem row.
--
-- Strictly additive:
--   - Both columns nullable.
--   - No data migration; existing rows have NULL LOINC and the CDS
--     service falls back to the in-process ProblemLoincBindings seed
--     table when the entity carries no explicit LOINC.
--   - LOINC code shape (n{1,7}-d) is validated at the application
--     layer by TerminologyCodes.normalizeAndRequireValidLoinc. A
--     database CHECK constraint is intentionally NOT added in this
--     migration — historical PatientProblem rows ingested by FHIR
--     read operations may carry malformed values that we don't want
--     to block at the schema layer in the same release that introduces
--     the column. A follow-on PR can ADD CONSTRAINT once a clean-up
--     UPDATE has been run, matching the pattern used by V93 for
--     rxnorm_code.
--
-- No automated rollback declared. Operators reverting must DROP COLUMN
-- and redeploy a JPA mapping that omits the fields in the same release.
-- =====================================================================

ALTER TABLE clinical.patient_problems
    ADD COLUMN IF NOT EXISTS loinc_code    VARCHAR(20)  NULL,
    ADD COLUMN IF NOT EXISTS loinc_display VARCHAR(255) NULL;
