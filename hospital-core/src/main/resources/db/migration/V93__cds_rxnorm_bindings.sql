-- V93: v1.0 / Clinical Safety / CDS Hooks expansion (roadmap row 2)
--
-- Strengthens the RxNorm binding on clinical.medication_catalog_items so the
-- new order-select / medication-prescribe CDS Hooks services can resolve a
-- catalog row from a FHIR CodeableConcept whose only typed coding is
-- {"system": "http://www.nlm.nih.gov/research/umls/rxnorm", "code": "..."}.
--
-- Adds (all additive, all idempotent):
--   1. A partial index on rxnorm_code for the active subset, so the new
--      forward lookup MedicationCatalogItemRepository.findByHospital_IdAnd-
--      RxnormCode is O(log n) without a full table scan.
--   2. A CHECK constraint pinning rxnorm_code to the same shape that
--      com.example.hms.terminology.TerminologyCodes.RXNORM enforces in
--      Java (1–12 digits). Pre-flight UPDATE NULLs out any existing row
--      that violates the regex so the constraint addition is safe even
--      against historical seed data.
--   3. tall_man_name VARCHAR(200) — optional ISMP-style "tall-man" lettering
--      for confusable drug pairs (e.g. "predniSONE" vs "prednisoLONE").
--      Surfaced by MedicationPrescribeRulesCdsService in card detail when
--      present; null is the documented "no special rendering" sentinel.
--
-- Why no external HTTP fallback (RxNav): the project targets intermittent
-- internet West African deployments (see V63 header) — same rationale as
-- the seed-list approach for drug_interactions. The catalog crosswalk +
-- partial index keeps every CDS evaluation entirely in-process.

-- 1. Pre-flight: NULL out any malformed rxnorm_code so the CHECK constraint
--    can be added safely. The validator regex is identical to
--    TerminologyCodes.RXNORM ("^[0-9]{1,12}$").
UPDATE clinical.medication_catalog_items
   SET rxnorm_code = NULL
 WHERE rxnorm_code IS NOT NULL
   AND rxnorm_code !~ '^[0-9]{1,12}$';

-- 2. CHECK constraint — guarded so re-applying the migration is a no-op.
--    Postgres has no portable ADD CONSTRAINT IF NOT EXISTS, so we use the
--    same DO $$ + pg_constraint pattern V63 / V77 use elsewhere.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'chk_med_catalog_rxnorm_format'
           AND conrelid = 'clinical.medication_catalog_items'::regclass
    ) THEN
        ALTER TABLE clinical.medication_catalog_items
            ADD CONSTRAINT chk_med_catalog_rxnorm_format
            CHECK (rxnorm_code IS NULL OR rxnorm_code ~ '^[0-9]{1,12}$');
    END IF;
END
$$;

-- 3. Partial index for the active rxnorm-keyed lookup (CDS hook fast path).
CREATE INDEX IF NOT EXISTS idx_med_catalog_rxnorm_active
    ON clinical.medication_catalog_items (rxnorm_code)
    WHERE active = TRUE AND rxnorm_code IS NOT NULL;

-- 4. tall_man_name — optional ISMP confusable-pair display string.
ALTER TABLE clinical.medication_catalog_items
    ADD COLUMN IF NOT EXISTS tall_man_name VARCHAR(200);
