-- V282: QA D1525. observations_stated separates the reports that existed before D1525 (FALSE: legacy) from every
-- report written after it (TRUE). Before D1525 the server and the templates filled values nobody stated: cardiac
-- activity, movement and tone TRUE, anomalies detected FALSE, one fetus, the category NORMAL, the template's
-- gestational age. On a legacy row exactly those values read as unknown (LegacyUltrasoundDefaults); every other
-- value was typed and is kept. A row is legacy only because it existed when this ran: every existing row is set
-- FALSE, the column then defaults to TRUE, and the entity creates every report as stated. No other data is
-- rewritten. Every pre-V282 row predates D1525 in practice (dev was wiped on 2026-11-08, prod never had #1090).
-- Edited in place before being applied anywhere shared (dev is paused on develop 537132bb2, without #1093).
-- Idempotent: the backfill only touches rows still NULL.

ALTER TABLE clinical.ultrasound_reports
    ADD COLUMN IF NOT EXISTS observations_stated BOOLEAN;

UPDATE clinical.ultrasound_reports
    SET observations_stated = FALSE
    WHERE observations_stated IS NULL;

ALTER TABLE clinical.ultrasound_reports
    ALTER COLUMN observations_stated SET DEFAULT TRUE;

ALTER TABLE clinical.ultrasound_reports
    ALTER COLUMN observations_stated SET NOT NULL;
