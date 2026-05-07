-- V92: enforce 1:1 between Encounter and DischargeSummary
--
-- Background. The portal-side backfill in
-- DischargeSummaryServiceImpl.getDischargeSummariesForPortalPatient()
-- (introduced in feat/avs-mobile-backfill-observability) ran without any
-- DB-level guarantee that an encounter could carry only one discharge
-- summary row. Two concurrent mobile loads of /me/patient/after-visit-summaries
-- could both observe the same orphan COMPLETED encounter, both create a
-- new DischargeSummary, and produce duplicate rows for the same
-- encounter_id. (Copilot review on PR #259 flagged this race.)
--
-- This migration:
--   1. Defensively dedupes any pre-existing duplicates by encounter_id,
--      keeping the row with the lowest `created_at` (i.e. the original) and
--      deleting newer copies. The intent is that the original is the most
--      authoritative — newer duplicates only exist if the race already fired
--      in production. The dedupe is wrapped in a CTE so child collections
--      (medication_reconciliation_entries, follow_up_appointments,
--      pending_test_results, equipment_and_supplies) cascade with
--      `ON DELETE` rules already declared in V1.
--
--   2. Adds the unique constraint that should have been there from the
--      start. From this point forward concurrent backfill calls will see
--      one of them succeed and the other surface as a
--      DataIntegrityViolationException, which the service code now catches
--      and downgrades to a no-op (the existing row is then returned by the
--      enrichment pass).
--
-- Rollback. To revert, drop the constraint:
--   ALTER TABLE clinical.discharge_summaries
--     DROP CONSTRAINT uk_discharge_summaries_encounter_id;
-- (The dedupe step is irreversible — back up the table first if you need
-- to roll back to the multi-row state.)

-- Step 1 — dedupe pre-existing duplicates.
WITH ranked AS (
    SELECT id,
           encounter_id,
           ROW_NUMBER() OVER (
               PARTITION BY encounter_id
               ORDER BY created_at ASC, id ASC
           ) AS rn
    FROM clinical.discharge_summaries
)
DELETE FROM clinical.discharge_summaries
 WHERE id IN (SELECT id FROM ranked WHERE rn > 1);

-- Step 2 — add the unique constraint.
-- ADD CONSTRAINT IF NOT EXISTS isn't supported in older Postgres, so use a
-- DO block that's idempotent across re-runs.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'uk_discharge_summaries_encounter_id'
    ) THEN
        ALTER TABLE clinical.discharge_summaries
            ADD CONSTRAINT uk_discharge_summaries_encounter_id
            UNIQUE (encounter_id);
    END IF;
END$$;
