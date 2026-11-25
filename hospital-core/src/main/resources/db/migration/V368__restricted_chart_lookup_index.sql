-- V368 (QA D3863): restricted charts are now enforced on every chart read by one central gate,
-- which first asks "is any chart restricted at all?" on each staff answer. A partial index over the
-- few restricted rows keeps that question, and the per-answer IN lookup, off the full patients table.
CREATE INDEX IF NOT EXISTS idx_patients_chart_restricted
    ON clinical.patients (id)
    WHERE chart_restricted = TRUE;
