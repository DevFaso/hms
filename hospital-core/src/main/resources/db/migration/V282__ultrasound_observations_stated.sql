-- V282: QA D1525. TRUE on a report written by a client that states its fetal observations (cardiac activity,
-- movement, tone), its anomalies answer and its fetus count: true, false or null meaning "not assessed".
-- Every existing row gets NULL, including any saved after #1090 and before this column: the legacy reading
-- (LegacyUltrasoundDefaults) is exact for both kinds of row, because it only reads as unknown the values the
-- old code could have defaulted - TRUE flags, anomalies FALSE, one fetus, the category NORMAL and the template's
-- gestational age while the row keeps the template's signature. A FALSE flag, two fetuses or any other value was
-- typed by someone and is kept. No data is rewritten. Idempotent. (Edited in place before being applied anywhere
-- shared; develop's dev was paused on 537132bb2.)

ALTER TABLE clinical.ultrasound_reports
    ADD COLUMN IF NOT EXISTS observations_stated BOOLEAN;
