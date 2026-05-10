-- V95: Medication catalog becomes a platform / national-list catalog.
--
-- Background:
-- The original V43 schema modelled `medication_catalog_items` as strictly
-- hospital-scoped (hospital_id NOT NULL, UNIQUE (hospital_id, code)), which
-- forced every hospital to redundantly insert "Amoxicillin", "Paracetamol",
-- etc. — duplicating identifying drug metadata (ATC code, RxNorm, generic
-- name) that the Burkina Faso Liste Nationale des Médicaments Essentiels
-- (LNME) already defines once nationally.
--
-- This migration relaxes hospital_id to optional so a single super-admin
-- can curate the platform catalog (hospital_id IS NULL = "global / LNME
-- entry, visible to every tenant"), while leaving the door open for
-- hospital-specific entries (hospital_id IS NOT NULL = "this hospital
-- carries an additional or substituted SKU"). Existing hospital-scoped
-- rows are left untouched; the system tolerates both shapes during the
-- transition.
--
-- Uniqueness changes from a single composite constraint to two partial
-- unique indexes so the two scopes can't shadow each other:
--   * exactly one global entry per `code` (hospital_id IS NULL)
--   * exactly one hospital entry per `code` within a hospital
--     (hospital_id IS NOT NULL)
--
-- See also PR #293 which made GET /api/medication-catalog tolerant of
-- super-admin global view (no hospitalId query param); this PR closes the
-- circle by letting POST create global entries as well.

-- 1) Hospital becomes optional.
ALTER TABLE clinical.medication_catalog_items
    ALTER COLUMN hospital_id DROP NOT NULL;

-- 2) Replace the composite UNIQUE (hospital_id, code) constraint, which
--    cannot enforce "no duplicate global codes" once hospital_id is NULL,
--    with two partial unique indexes covering the two scopes separately.
--    Postgres treats NULLs as distinct in a multi-column UNIQUE — without
--    this split, multiple globals with the same code would silently coexist.
ALTER TABLE clinical.medication_catalog_items
    DROP CONSTRAINT IF EXISTS uq_mci_code_hospital;

CREATE UNIQUE INDEX IF NOT EXISTS uq_mci_code_global
    ON clinical.medication_catalog_items (code)
    WHERE hospital_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_mci_code_hospital
    ON clinical.medication_catalog_items (hospital_id, code)
    WHERE hospital_id IS NOT NULL;
