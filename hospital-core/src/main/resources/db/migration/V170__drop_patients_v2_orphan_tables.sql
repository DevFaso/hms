-- V170: drop the orphan patients_v2 stack -- but only where it is empty.
--
-- WHY: public.patients_v2, public.patient_insurances_v2 and
-- public.patient_medical_histories_v2 (V1) back an unfinished second Patient
-- model (com.example.hms.patient.model, entity names PatientV2 /
-- PatientInsuranceV2 / PatientMedicalHistoryV2). Their only reader and
-- writer was the /patients-v2 controller, which had no @PreAuthorize and no
-- hospital column to scope by; #785 removed it rather than gate it, and
-- PatientsV2RemovedSecurityIT keeps it removed. This release deletes the
-- three entities too. Nothing else reads the tables: no repository, no JPQL,
-- no native query, no view and no foreign key names them (checked against
-- the code and the fully migrated catalog, 2026-09-26). Kept, they are a
-- plaintext PHI surface (names, DOB, address, phone, e-mail, allergies,
-- conditions, medications) that no screen shows, no audit covers and no
-- patient ROI or erasure request reaches.
--
-- FAIL-SAFE, not unconditional: the endpoint was reachable by any
-- authenticated token until #785, so an environment MAY hold rows in them.
-- A table is dropped only when it is EMPTY. A table with rows is left exactly
-- as it is, with a WARNING naming it and its row count; dropping it is then
-- a human decision (export first if anything in it matters), by hand:
--     DROP TABLE public.<table>;
-- The application no longer maps these tables at all, so leaving one in
-- place changes nothing at runtime.
--
-- Idempotent (to_regclass guard). Postgres only. No automated rollback: an
-- empty table is recreatable from V1 if ever wanted, which it should not be.

DO $$
DECLARE
    target    TEXT;
    targets   TEXT[] := ARRAY[
        'public.patient_insurances_v2',
        'public.patient_medical_histories_v2',
        'public.patients_v2'
    ];
    row_count BIGINT;
    dropped   INT := 0;
    kept      INT := 0;
BEGIN
    FOREACH target IN ARRAY targets LOOP
        IF to_regclass(target) IS NULL THEN
            RAISE NOTICE 'V170: % does not exist here — nothing to do.', target;
            CONTINUE;
        END IF;

        EXECUTE format('SELECT count(*) FROM %s', target) INTO row_count;

        IF row_count > 0 THEN
            RAISE WARNING 'V170: % holds % row(s) and was NOT dropped. Nothing reads it any more; '
                          'review (export if needed) and drop it by hand: DROP TABLE %;',
                          target, row_count, target;
            kept := kept + 1;
            CONTINUE;
        END IF;

        EXECUTE format('DROP TABLE %s', target);
        RAISE NOTICE 'V170: % was empty and has been dropped.', target;
        dropped := dropped + 1;
    END LOOP;

    RAISE NOTICE 'V170: done — % table(s) dropped, % kept because they hold rows.', dropped, kept;
END $$;
