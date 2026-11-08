-- V281: QA D1516. A death closes the patient's open stays, visits and appointments at its hospital. When the death was
-- later marked entered in error nothing said which ones it had closed, so they could neither be flagged nor named to a
-- clinician. The record now keeps their ids, by kind. Rows already written hold none: the service falls back to what
-- can still be read (the stays it marked DECEASED, the visit it recorded, the appointments it noted). Idempotent.

ALTER TABLE clinical.death_records
    ADD COLUMN IF NOT EXISTS closed_records JSONB;
