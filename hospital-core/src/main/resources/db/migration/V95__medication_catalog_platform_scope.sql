-- V95: Medication catalog becomes a platform / national-list catalog.
--
-- Background:
-- The original V43 schema modelled `medication_catalog_items` as strictly
-- hospital-scoped (hospital_id NOT NULL, UNIQUE (hospital_id, code)), which
-- forced every hospital to redundantly insert "Amoxicillin", "Paracétamol",
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
-- Uniqueness is restructured to follow the natural key for each scope:
--   * Hospital-scoped rows: the entity field `code` (documented as the
--     "internal formulary code", i.e. the hospital's SKU). Preserves the
--     V43 invariant that two SKUs within the same hospital cannot share
--     a code. Narrowed to rows where `code` is actually populated so the
--     transition from V43's blanket NOT NULL stays compatible with the
--     current DTO (which does not yet expose `code`).
--   * Global rows: `rxnorm_code` (the universal pharmaceutical
--     reference). This is the identifier the CDS hooks already query
--     (see V93's idx_med_catalog_rxnorm_active), so binding global
--     uniqueness to it keeps prescribing decisions aligned with the
--     national catalog.
--
-- See also PR #293 which made GET /api/medication-catalog tolerant of
-- super-admin global view (no hospitalId query param); this PR closes the
-- circle by letting POST create global entries as well.

-- 1) Hospital becomes optional.
ALTER TABLE clinical.medication_catalog_items
    ALTER COLUMN hospital_id DROP NOT NULL;

-- 2) `code` becomes optional. The entity has always permitted null (no
--    @NotBlank / nullable=false), but V43's NOT NULL forced every row to
--    carry one. Existing rows already have a code; new global rows
--    created via the API legitimately don't (RxNorm is their natural
--    identifier — see step 4 below).
ALTER TABLE clinical.medication_catalog_items
    ALTER COLUMN code DROP NOT NULL;

-- 3) Replace the V43 composite UNIQUE (hospital_id, code) constraint
--    with a partial unique index that preserves the V43 invariant
--    (no two SKUs in the same hospital share a code) but tolerates
--    rows where `code` is null — which globals legitimately are, and
--    which Postgres would otherwise treat as distinct anyway in a
--    multi-column UNIQUE, silently letting duplicates slip through.
ALTER TABLE clinical.medication_catalog_items
    DROP CONSTRAINT IF EXISTS uq_mci_code_hospital;

CREATE UNIQUE INDEX IF NOT EXISTS uq_mci_code_hospital
    ON clinical.medication_catalog_items (hospital_id, code)
    WHERE hospital_id IS NOT NULL AND code IS NOT NULL;

-- 4) Enforce one global row per RxNorm so super-admins cannot insert
--    two "Amoxicillin 500 mg" entries pointing at the same RxCUI.
--    rxnorm_code is the universal pharmaceutical identifier; the CDS
--    hook lookups (V93's findActiveByHospitalIdAndRxnormCode) already
--    rely on it. The index is partial: globals without RxNorm fall
--    outside its scope, and so do hospital-scoped rows (their
--    uniqueness lives in uq_mci_code_hospital above).
CREATE UNIQUE INDEX IF NOT EXISTS uq_mci_rxnorm_global
    ON clinical.medication_catalog_items (rxnorm_code)
    WHERE hospital_id IS NULL AND rxnorm_code IS NOT NULL;
