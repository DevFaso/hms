-- V282: QA D1525. A finding is charted only when the clinician states it.
--
-- Before D1525 the server and the templates filled values nobody stated: cardiac activity, movement and tone TRUE,
-- anomalies detected FALSE, one fetus, and the template's gestational age (12 or 20 weeks). This migration
--   1. adds observations_stated: FALSE on every report that exists when it runs (legacy). The column defaults to
--      FALSE (NOT NULL) so a row an older application instance writes during a rolling deploy also lands as legacy.
--      The new code sets TRUE explicitly on every report it creates, and on a legacy row once a save changes one
--      of its observations,
--   2. copies the original six values (and the hospital) of every row the data step changes into
--      ultrasound_report_legacy_backup. It cascades with its report and is kept until the prod data has been
--      checked, then dropped by a later migration (tasklist.md, standing debt),
--   3. on the legacy rows NOT yet reviewed, sets to NULL exactly the values the old code could have defaulted, so
--      every reader, export and query sees plain values. A typed value the old code could not have defaulted is
--      kept: a FALSE flag, anomalies TRUE, two fetuses, a gestational age outside the template signature.
-- The screen reads a NULL on a legacy row as "No answer (recorded before this was asked)", on a stated row as
-- "Not assessed".
--
-- Reviewed reports (report_reviewed_by_provider) are signed and cannot be edited: the data step leaves them as
-- signed. They keep observations_stated = FALSE for the legacy display.
--
-- Known limits (tasklist.md, standing debt): a reviewed legacy report may still carry a default "present", "no
-- anomalies" or "1 fetus" as signed. finding_category NORMAL on a legacy row is kept (NOT NULL column, usually a
-- deliberate choice). The template gestational age is cleared only when every clinician-enterable column is at
-- its template or empty state (UltrasoundTemplateSignatureColumnsTest pins the list against the entity).
--
-- Dev data: dev was wiped after #1090 deployed, so its rows are post-#1090 and this step may null some TRUE flags
-- or one-fetus counts a QA tester did state (the backup keeps them). Accepted: test data only. Prod never had
-- #1090, so every prod row predates D1525. Edited in place before being applied anywhere shared (dev is paused on
-- develop 537132bb2, without #1093).
--
-- Restore: the backup holds the original six values of every row the step changed. To put them back on rows that
-- were not saved since (a row saved since is TRUE and holds newer values), run
--   UPDATE clinical.ultrasound_reports r SET fetal_cardiac_activity = b.fetal_cardiac_activity,
--     fetal_movement_observed = b.fetal_movement_observed, fetal_tone_normal = b.fetal_tone_normal,
--     anomalies_detected = b.anomalies_detected, number_of_fetuses = b.number_of_fetuses,
--     gestational_age_at_scan = b.gestational_age_at_scan
--   FROM clinical.ultrasound_report_legacy_backup b WHERE b.id = r.id AND r.observations_stated = FALSE
--
-- Idempotent and re-runnable: the template-signature ids are computed once (a session temp table) and used by both
-- the backup and the gestational-age update, the backup skips ids already copied, the data step only touches legacy
-- unreviewed rows and only writes NULLs. Plain statements, no DO block (splitStatements stays true).

ALTER TABLE clinical.ultrasound_reports
    ADD COLUMN IF NOT EXISTS observations_stated BOOLEAN;

UPDATE clinical.ultrasound_reports
    SET observations_stated = FALSE
    WHERE observations_stated IS NULL;

ALTER TABLE clinical.ultrasound_reports
    ALTER COLUMN observations_stated SET DEFAULT FALSE;

ALTER TABLE clinical.ultrasound_reports
    ALTER COLUMN observations_stated SET NOT NULL;

CREATE TABLE IF NOT EXISTS clinical.ultrasound_report_legacy_backup (
    id UUID PRIMARY KEY REFERENCES clinical.ultrasound_reports (id) ON DELETE CASCADE,
    hospital_id UUID NOT NULL,
    fetal_cardiac_activity BOOLEAN,
    fetal_movement_observed BOOLEAN,
    fetal_tone_normal BOOLEAN,
    anomalies_detected BOOLEAN,
    number_of_fetuses INTEGER,
    gestational_age_at_scan INTEGER,
    migrated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

-- The rows whose gestational age is the template's: every clinician-enterable column at its template or empty state.
-- Computed once, so the backup and the update below cannot drift.
DROP TABLE IF EXISTS v282_template_ga;

CREATE TEMP TABLE v282_template_ga AS
    SELECT id FROM clinical.ultrasound_reports
    WHERE observations_stated = FALSE
      AND report_reviewed_by_provider IS NOT TRUE
      AND gestational_age_at_scan IN (12, 20)
      AND gestational_age_days IS NULL
      AND nuchal_translucency_mm IS NULL
      AND crown_rump_length_mm IS NULL
      AND nasal_bone_present IS NULL
      AND estimated_due_date IS NULL
      AND (due_date_confirmed IS NULL OR due_date_confirmed = FALSE)
      AND (number_of_fetuses IS NULL OR number_of_fetuses = 1)
      AND COALESCE(BTRIM(fetal_position), '') = ''
      AND biparietal_diameter_mm IS NULL
      AND head_circumference_mm IS NULL
      AND abdominal_circumference_mm IS NULL
      AND femur_length_mm IS NULL
      AND estimated_fetal_weight_grams IS NULL
      AND COALESCE(BTRIM(placental_location), '') = ''
      AND COALESCE(BTRIM(placental_grade), '') = ''
      AND amniotic_fluid_index IS NULL
      AND COALESCE(BTRIM(amniotic_fluid_level), '') = ''
      AND cervical_length_mm IS NULL
      AND COALESCE(BTRIM(umbilical_artery_doppler), '') = ''
      AND COALESCE(BTRIM(uterine_artery_doppler), '') = ''
      AND fetal_heart_rate IS NULL
      AND (fetal_cardiac_activity IS NULL OR fetal_cardiac_activity = TRUE)
      AND (fetal_movement_observed IS NULL OR fetal_movement_observed = TRUE)
      AND (fetal_tone_normal IS NULL OR fetal_tone_normal = TRUE)
      AND (anatomy_survey_complete IS NULL OR anatomy_survey_complete = FALSE)
      AND COALESCE(BTRIM(anatomy_findings), '') = ''
      AND finding_category = 'NORMAL'
      AND COALESCE(BTRIM(findings_summary), '') = ''
      AND COALESCE(BTRIM(interpretation), '') = ''
      AND (anomalies_detected IS NULL OR anomalies_detected = FALSE)
      AND COALESCE(BTRIM(anomaly_description), '') = ''
      AND (genetic_screening_recommended IS NULL OR genetic_screening_recommended = FALSE)
      AND COALESCE(BTRIM(genetic_screening_type), '') = ''
      AND (follow_up_required IS NULL OR follow_up_required = FALSE)
      AND COALESCE(BTRIM(follow_up_recommendations), '') = ''
      AND (specialist_referral_needed IS NULL OR specialist_referral_needed = FALSE)
      AND COALESCE(BTRIM(specialist_referral_type), '') = ''
      AND next_ultrasound_recommended_weeks IS NULL
      AND COALESCE(BTRIM(provider_review_notes), '') = '';

-- The original values of every row the statements below change, before they change it.
INSERT INTO clinical.ultrasound_report_legacy_backup (id, hospital_id, fetal_cardiac_activity,
        fetal_movement_observed, fetal_tone_normal, anomalies_detected, number_of_fetuses, gestational_age_at_scan,
        migrated_at)
    SELECT id, hospital_id, fetal_cardiac_activity, fetal_movement_observed, fetal_tone_normal, anomalies_detected,
           number_of_fetuses, gestational_age_at_scan, NOW()
    FROM clinical.ultrasound_reports
    WHERE observations_stated = FALSE
      AND report_reviewed_by_provider IS NOT TRUE
      AND (fetal_cardiac_activity = TRUE
           OR fetal_movement_observed = TRUE
           OR fetal_tone_normal = TRUE
           OR anomalies_detected = FALSE
           OR number_of_fetuses = 1
           OR id IN (SELECT id FROM v282_template_ga))
    ON CONFLICT (id) DO NOTHING;

-- The template's gestational age, only while every clinician-enterable column is at its template or empty state
-- (the ids were computed before any flag was cleared).
UPDATE clinical.ultrasound_reports
    SET gestational_age_at_scan = NULL
    WHERE id IN (SELECT id FROM v282_template_ga);

UPDATE clinical.ultrasound_reports
    SET fetal_cardiac_activity = NULL
    WHERE observations_stated = FALSE
      AND report_reviewed_by_provider IS NOT TRUE
      AND fetal_cardiac_activity = TRUE;

UPDATE clinical.ultrasound_reports
    SET fetal_movement_observed = NULL
    WHERE observations_stated = FALSE
      AND report_reviewed_by_provider IS NOT TRUE
      AND fetal_movement_observed = TRUE;

UPDATE clinical.ultrasound_reports
    SET fetal_tone_normal = NULL
    WHERE observations_stated = FALSE
      AND report_reviewed_by_provider IS NOT TRUE
      AND fetal_tone_normal = TRUE;

UPDATE clinical.ultrasound_reports
    SET anomalies_detected = NULL
    WHERE observations_stated = FALSE
      AND report_reviewed_by_provider IS NOT TRUE
      AND anomalies_detected = FALSE;

UPDATE clinical.ultrasound_reports
    SET number_of_fetuses = NULL
    WHERE observations_stated = FALSE
      AND report_reviewed_by_provider IS NOT TRUE
      AND number_of_fetuses = 1;

DROP TABLE IF EXISTS v282_template_ga;
