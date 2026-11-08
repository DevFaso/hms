-- V282: QA D1525. A report saved from now on records that its fetal observations (cardiac activity, movement,
-- tone) and its anomalies answer were STATED by the clinician: true, false or null meaning "not assessed".
-- Rows written before carry NULL here: their TRUE flags (and "no anomalies", "1 fetus") were server defaults
-- nobody stated, so they are read as "not stated (legacy)" and never re-sent as a statement on edit.
-- No data is rewritten. Idempotent.

ALTER TABLE clinical.ultrasound_reports
    ADD COLUMN IF NOT EXISTS observations_stated BOOLEAN;
