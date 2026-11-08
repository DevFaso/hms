-- V280: CR-08. A perinatal classification could not be checked against the facts: this record held no birth time,
-- gestation or birth status. All are optional facts, and unknown is a real state. Rows already written are not
-- assessed and not rewritten: they read UNKNOWN. Idempotent by rule.
--
-- A delivery documented with its time left blank stores the moment of documenting as the birth. A death's age must
-- not be counted from that, so the delivery now says whether the time was stated. Rows already written cannot tell,
-- so they read false and a death falls back to the dates, which claims no precision it may not have.

ALTER TABLE clinical.death_records
    ADD COLUMN IF NOT EXISTS born_at               TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS birth_status          VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN IF NOT EXISTS gestational_age_weeks INTEGER,
    ADD COLUMN IF NOT EXISTS perinatal_age_basis   VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN';

ALTER TABLE clinical.delivery_records
    ADD COLUMN IF NOT EXISTS birth_time_stated BOOLEAN NOT NULL DEFAULT false;
