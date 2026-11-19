-- =====================================================================
-- V315: the legacy free-text allergy import ledger
--       (clinical.patient_allergy_legacy_imports), QA D2220.
--
-- LegacyAllergyTextBackfill runs on every start. It decided that a patient
-- was "done" by inferring it from patient_allergies (a row with source
-- LEGACY_FREE_TEXT or REGISTRATION_FREE_TEXT). A patient whose allergies
-- were recorded by hand still had text in patients.allergies (the derived
-- summary), matched nothing, was "imported" again on every boot with zero
-- rows created, and the log said "N imported" each time. Worse, an allergy a
-- clinician later deactivated or marked entered-in-error could come back
-- as an active UNCONFIRMED row on the next deploy.
--
-- One row per patient the backfill has processed, written in the same
-- transaction as the import itself. The candidate query skips every patient
-- with a row here, so the import runs at most once per patient, whatever it
-- created. No PHI: a patient id, a fixed outcome code and a count.
--   * outcome: IMPORTED | NOTHING_NEW | NOTHING_TO_IMPORT | ALREADY_DONE.
--     No CHECK, so a later outcome code needs no migration.
--
-- Additive: one table, IF NOT EXISTS, no DO block.
-- =====================================================================

CREATE TABLE IF NOT EXISTS clinical.patient_allergy_legacy_imports (
    patient_id   UUID        NOT NULL PRIMARY KEY
                             REFERENCES clinical.patients (id) ON DELETE CASCADE,
    outcome      VARCHAR(32) NOT NULL,
    rows_created INTEGER     NOT NULL DEFAULT 0,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
