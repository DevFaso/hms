-- V176: one education progress row per (patient, resource), enforced by a
-- unique key.
--
-- WHY: clinical.patient_education_progress never had a unique key on
-- (patient_id, resource_id), so the staff "track progress" write could insert
-- a second row for material a patient already had (two requests racing past
-- the "is there a row?" read). #790 made every reader and writer pick the
-- same row when there are several (EducationProgressRows). This adds the key
-- so there can no longer be several; the write that creates the row
-- (EducationProgressWrites) treats a lost race on it as "the row exists".
--
-- WHAT THIS DOES, in one transaction:
--   1. Locks the table in ACCESS EXCLUSIVE mode, the lock ADD CONSTRAINT
--      takes anyway, taken first so there is no lock upgrade to deadlock on
--      and no row can be added between the check and the key.
--   2. If any (patient_id, resource_id) has more than one row, STOPS with an
--      exception giving the number of such groups (no ids, no content).
--      Nothing is deleted or changed. Which row to keep, and what to carry
--      over from the others, is a decision for a person; once the
--      duplicates are resolved, the deploy is re-run.
--   3. Otherwise adds uk_patient_education_progress_patient_resource UNIQUE
--      (patient_id, resource_id). The table has no soft-delete flag, so the
--      key is not partial. The entity declares the same key, so the H2 test
--      schema carries it too.
--
-- Decision (2026-10-04, revised 2026-10-05): stop on duplicates rather than
-- merge them. No environment has any (dev and prod have no rows in this
-- table today), and a lossless stop beats an automatic merge nobody needs.
--
-- Idempotent: the key is added only if it is not already there. Rollback:
--   ALTER TABLE clinical.patient_education_progress
--     DROP CONSTRAINT uk_patient_education_progress_patient_resource;

DO $$
DECLARE
    duplicate_groups BIGINT;
BEGIN
    IF to_regclass('clinical.patient_education_progress') IS NULL THEN
        RAISE NOTICE 'V176: clinical.patient_education_progress does not exist here, skipped.';
        RETURN;
    END IF;

    LOCK TABLE clinical.patient_education_progress IN ACCESS EXCLUSIVE MODE;

    IF EXISTS (
        SELECT 1
          FROM pg_constraint c
         WHERE c.conrelid = 'clinical.patient_education_progress'::regclass
           AND c.conname = 'uk_patient_education_progress_patient_resource'
    ) THEN
        RAISE NOTICE 'V176: uk_patient_education_progress_patient_resource already present.';
        RETURN;
    END IF;

    SELECT count(*)
      INTO duplicate_groups
      FROM (SELECT 1
              FROM clinical.patient_education_progress p
             GROUP BY p.patient_id, p.resource_id
            HAVING count(*) > 1) g;

    IF duplicate_groups > 0 THEN
        RAISE EXCEPTION 'V176: % (patient, resource) group(s) in clinical.patient_education_progress have more than one row. Nothing was changed. A person must resolve the duplicates (keep one row per patient and resource) before this migration can add uk_patient_education_progress_patient_resource; then re-run the deploy.', duplicate_groups;
    END IF;

    ALTER TABLE clinical.patient_education_progress
        ADD CONSTRAINT uk_patient_education_progress_patient_resource
        UNIQUE (patient_id, resource_id);
    RAISE NOTICE 'V176: uk_patient_education_progress_patient_resource added.';
END $$;
