-- V247 (round 18, QA D935): the paediatric dose check as real hospitals run it.
--
-- The catalogue held one number, pediatric_max_dose_mg_per_kg (mg/kg per
-- dose, V63), nobody but an administrator could write it, and it was null on
-- every row. A real formulary states, per drug and per hospital:
--   * a usual maximum per dose (mg/kg, and an absolute mg cap) and per day
--     (mg/kg) - exceeding one is a SOFT stop the prescriber may override with
--     a reason that is audited;
--   * an absolute daily maximum in mg - exceeding it is a HARD stop;
--   * weight bands instead of mg/kg for some drugs (artemether-lumefantrine);
--   * an age below which no automatic mg/kg verdict is given (3 months by
--     default, 1 year for morphine) and a manual review is required;
--   * where each value comes from and whether the pharmacy lead has approved
--     it locally.
-- These are per-hospital formulary data. No value is seeded here: the
-- defaults are loaded on dev by a script through the API, and each hospital's
-- pharmacy approves or changes them.
--
-- Plain statements, no DO block. Idempotent.

ALTER TABLE clinical.medication_catalog_items
    ADD COLUMN IF NOT EXISTS pediatric_max_daily_mg_per_kg NUMERIC(8,3);
ALTER TABLE clinical.medication_catalog_items
    ADD COLUMN IF NOT EXISTS pediatric_max_single_dose_mg NUMERIC(10,3);
ALTER TABLE clinical.medication_catalog_items
    ADD COLUMN IF NOT EXISTS pediatric_max_daily_mg NUMERIC(10,3);
ALTER TABLE clinical.medication_catalog_items
    ADD COLUMN IF NOT EXISTS pediatric_manual_review_under_months INTEGER;
ALTER TABLE clinical.medication_catalog_items
    ADD COLUMN IF NOT EXISTS pediatric_weight_bands TEXT;
ALTER TABLE clinical.medication_catalog_items
    ADD COLUMN IF NOT EXISTS pediatric_dose_source VARCHAR(500);
ALTER TABLE clinical.medication_catalog_items
    ADD COLUMN IF NOT EXISTS pediatric_dose_status VARCHAR(40);
ALTER TABLE clinical.medication_catalog_items
    ADD COLUMN IF NOT EXISTS pediatric_dose_reviewed_by UUID;
ALTER TABLE clinical.medication_catalog_items
    ADD COLUMN IF NOT EXISTS pediatric_dose_reviewed_at TIMESTAMP WITHOUT TIME ZONE;
