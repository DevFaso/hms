-- V122: height + head circumference on the vitals bundle (P3 item 18, growth charts).
--
-- WHY: clinical.patient_vital_signs has carried weight_kg since V1 but no height
-- and no head circumference, so the portal triage form's heightCm field has been
-- accepted and silently DISCARDED since it shipped (TriageSubmissionRequestDTO
-- had zero readers), and a growth chart had exactly one plottable metric.
-- Length/height-for-age and head-circumference-for-age need these columns.
--
-- Both nullable: a vitals row is a sparse measurement bundle — most adult
-- captures will never fill either. Units follow the house convention of baking
-- the unit into the column name (cm, matching weight_kg).
--
-- Idempotent by rule (a deploy killed mid-Liquibase must not wedge the
-- environment — see the V108/V110 incident).

ALTER TABLE clinical.patient_vital_signs
    ADD COLUMN IF NOT EXISTS height_cm DOUBLE PRECISION;

ALTER TABLE clinical.patient_vital_signs
    ADD COLUMN IF NOT EXISTS head_circumference_cm DOUBLE PRECISION;
