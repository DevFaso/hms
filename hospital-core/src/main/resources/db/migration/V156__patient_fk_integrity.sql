-- V156: stop DELETE /patients/{id} from orphaning a patient's chart.
--
-- WHY: PatientServiceImpl.deletePatient did a hard `deleteById`, cleaning only
-- patient_proxies. Nothing else stopped it, because 47 of the 82 tables that
-- carry a patient_id declare NO foreign key to clinical.patients -- including
-- clinical.consultations and public.admissions, both created unconstrained in
-- V1__Initial_Schema.sql and never fixed since.
--
-- The result showed up in the dev log on 2026-09-07 as:
--     Consultation(8f3cda33-...) has a dangling FK on 'patient' -- referenced
--     row was deleted. Returning null for this association; DB cleanup required.
-- three consultations and one admission, all still holding PHI (notes,
-- diagnoses) with no patient identity attached: unattributable in an audit,
-- unreachable by a patient's ROI or erasure request, and rendered as nulls in
-- clinical lists. Ten files across the codebase carry defensive
-- "dangling FK -> return null" handling (JpaProxyUtils, six mappers, two
-- repositories) -- the symptom was made survivable at read time instead of
-- being prevented at write time.
--
-- WHAT THIS DOES: adds the FK that the JPA mapping has always implied, on the
-- core clinical tables where an orphan is a patient-safety problem. Omitting
-- ON DELETE gives NO ACTION, deliberately: a chart must never disappear
-- because someone deleted the patient row. The application now refuses the
-- delete with a 409 before it reaches the database; this is the backstop that
-- makes the refusal true even for callers that bypass the service.
--
-- NOT VALID, per the V115 precedent: existing orphans must not fail the deploy.
-- New and updated rows ARE checked, and so are deletes on clinical.patients --
-- NOT VALID only skips the one-off scan of pre-existing rows. Validate later,
-- once the orphans listed by the warnings below have been reconciled:
--     ALTER TABLE clinical.consultations VALIDATE CONSTRAINT fk_consultations_patient;
--
-- SCOPE: 10 tables here, not all 47. The rest are recorded as standing debt in
-- tasklist.md; several are legacy (`*_v2`, the patients_v2 orphan stack) or
-- deliberately unconstrained (empi.master_identities spans identities across
-- tenants), so each needs its own judgement rather than a blanket sweep.

DO $$
DECLARE
    target        TEXT;
    targets       TEXT[] := ARRAY[
        'clinical.consultations',
        'public.admissions',
        'clinical.encounters',
        'clinical.prescriptions',
        'clinical.patient_vital_signs',
        'clinical.patient_allergies',
        'clinical.patient_problems',
        'clinical.imaging_orders',
        'clinical.appointments',
        'lab.lab_orders'
    ];
    schema_name   TEXT;
    table_name    TEXT;
    fk_name       TEXT;
    orphan_count  BIGINT;
    added         INT := 0;
    skipped       INT := 0;
BEGIN
    FOREACH target IN ARRAY targets LOOP
        schema_name := split_part(target, '.', 1);
        table_name  := split_part(target, '.', 2);
        fk_name     := 'fk_' || table_name || '_patient';

        -- A table that does not exist on this database is a NOTICE, never a
        -- failure: the same changelog runs against local H2-shaped installs
        -- and against tenants provisioned at different times.
        IF to_regclass(target) IS NULL THEN
            RAISE NOTICE 'V156: % does not exist here — skipped.', target;
            skipped := skipped + 1;
            CONTINUE;
        END IF;

        IF EXISTS (
            SELECT 1
              FROM pg_constraint c
              JOIN pg_class     t ON t.oid = c.conrelid
              JOIN pg_namespace n ON n.oid = t.relnamespace
             WHERE c.conname  = fk_name
               AND n.nspname  = schema_name
               AND t.relname  = table_name
        ) THEN
            RAISE NOTICE 'V156: % already present — nothing to do.', fk_name;
            skipped := skipped + 1;
            CONTINUE;
        END IF;

        -- Report what is already broken before constraining the table, so the
        -- deploy log names the rows a human has to reconcile.
        EXECUTE format(
            'SELECT count(*) FROM %I.%I x '
            ' WHERE x.patient_id IS NOT NULL '
            '   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)',
            schema_name, table_name)
        INTO orphan_count;

        IF orphan_count > 0 THEN
            RAISE WARNING 'V156: % row(s) in % reference a patient that no longer exists. '
                          'The constraint is added NOT VALID so they do not fail this deploy; '
                          'they keep rendering with null patient details until reconciled.',
                          orphan_count, target;
        END IF;

        EXECUTE format(
            'ALTER TABLE %I.%I ADD CONSTRAINT %I '
            'FOREIGN KEY (patient_id) REFERENCES clinical.patients (id) NOT VALID',
            schema_name, table_name, fk_name);

        RAISE NOTICE 'V156: % added (NO ACTION, NOT VALID).', fk_name;
        added := added + 1;
    END LOOP;

    RAISE NOTICE 'V156: done — % constraint(s) added, % skipped.', added, skipped;
END $$;
