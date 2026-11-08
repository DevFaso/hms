-- V282: QA D1525. A finding is charted only when the clinician states it.
--
-- Before D1525 the server and the templates filled values nobody stated: cardiac activity, movement and tone TRUE,
-- anomalies detected FALSE, one fetus, and the template's gestational age (12 or 20 weeks). This migration
--   1. adds observations_stated: FALSE on every report that exists when it runs (legacy), TRUE by default for every
--      report written afterwards (the entity also creates reports as stated, any later save marks a row stated),
--   2. on the legacy rows only, sets to NULL exactly the values the old code could have defaulted, so every reader,
--      export and query sees plain values. A typed value the old code could not have defaulted is kept: a FALSE
--      flag, anomalies TRUE, two fetuses, a gestational age outside the template signature.
-- The screen reads a NULL on a legacy row as "No answer (recorded before this was asked)", on a stated row as
-- "Not assessed".
--
-- Known limits (tasklist.md, standing debt): finding_category NORMAL on a legacy row is kept as it is (the column
-- is NOT NULL and the category is usually a deliberate choice), a legacy template gestational age kept while
-- other fields were typed is kept too (the signature below is the only exact test).
--
-- Dev data: dev was wiped after #1090 deployed, so its rows are post-#1090 and this step may null some TRUE flags
-- or one-fetus counts a QA tester did state. Accepted: test data only. Prod never had #1090, so every prod row
-- predates D1525. Edited in place before being applied anywhere shared (dev is paused on develop 537132bb2,
-- without #1093).
--
-- Idempotent and re-runnable: the data step only touches rows still marked FALSE and only writes NULLs, a row saved
-- since is TRUE and never touched. Plain statements, no DO block (splitStatements stays true).

ALTER TABLE clinical.ultrasound_reports
    ADD COLUMN IF NOT EXISTS observations_stated BOOLEAN;

UPDATE clinical.ultrasound_reports
    SET observations_stated = FALSE
    WHERE observations_stated IS NULL;

ALTER TABLE clinical.ultrasound_reports
    ALTER COLUMN observations_stated SET DEFAULT TRUE;

ALTER TABLE clinical.ultrasound_reports
    ALTER COLUMN observations_stated SET NOT NULL;

-- The template's gestational age, only while the whole template signature holds (checked before the flags are
-- cleared, the flag test accepts TRUE or NULL, so the order does not matter on a re-run).
UPDATE clinical.ultrasound_reports
    SET gestational_age_at_scan = NULL
    WHERE observations_stated = FALSE
      AND gestational_age_at_scan IN (12, 20)
      AND gestational_age_days IS NULL
      AND COALESCE(BTRIM(findings_summary), '') = ''
      AND COALESCE(BTRIM(anatomy_findings), '') = ''
      AND COALESCE(BTRIM(interpretation), '') = ''
      AND COALESCE(BTRIM(anomaly_description), '') = ''
      AND biparietal_diameter_mm IS NULL
      AND head_circumference_mm IS NULL
      AND abdominal_circumference_mm IS NULL
      AND femur_length_mm IS NULL
      AND estimated_fetal_weight_grams IS NULL
      AND nuchal_translucency_mm IS NULL
      AND crown_rump_length_mm IS NULL
      AND fetal_heart_rate IS NULL
      AND (fetal_cardiac_activity IS NULL OR fetal_cardiac_activity = TRUE)
      AND (fetal_movement_observed IS NULL OR fetal_movement_observed = TRUE)
      AND (fetal_tone_normal IS NULL OR fetal_tone_normal = TRUE);

UPDATE clinical.ultrasound_reports
    SET fetal_cardiac_activity = NULL
    WHERE observations_stated = FALSE AND fetal_cardiac_activity = TRUE;

UPDATE clinical.ultrasound_reports
    SET fetal_movement_observed = NULL
    WHERE observations_stated = FALSE AND fetal_movement_observed = TRUE;

UPDATE clinical.ultrasound_reports
    SET fetal_tone_normal = NULL
    WHERE observations_stated = FALSE AND fetal_tone_normal = TRUE;

UPDATE clinical.ultrasound_reports
    SET anomalies_detected = NULL
    WHERE observations_stated = FALSE AND anomalies_detected = FALSE;

UPDATE clinical.ultrasound_reports
    SET number_of_fetuses = NULL
    WHERE observations_stated = FALSE AND number_of_fetuses = 1;
