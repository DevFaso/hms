-- V169: the patient foreign key on the tables V156 left unconstrained.
--
-- WHY: V156 constrained the ten core clinical tables after a hard
-- deleteById orphaned consultations and an admission on dev (2026-09-07).
-- A catalog scan of the fully migrated schema (2026-09-26) finds 36 more
-- base tables with a patient_id and no foreign key to clinical.patients.
-- The application refuses a patient delete that would orphan them
-- (PatientServiceImpl.deletePatient, 409), but only for callers that go
-- through the service; this is the backstop, exactly as V156 was.
--
-- WHAT THIS DOES: one FK per table, (patient_id) -> clinical.patients(id),
-- NO ACTION (the default, deliberately: a chart must never disappear because
-- someone deleted the patient row), NOT VALID per the V115/V156 precedent so
-- rows that are already orphaned do not fail the deploy. New and updated
-- rows, and deletes on clinical.patients, ARE checked; NOT VALID only skips
-- the one-off scan of existing rows. Each table's orphans are COUNTED and
-- reported as a WARNING before it is constrained -- nothing is deleted or
-- rewritten. Validate a table once its orphans are reconciled:
--     ALTER TABLE <schema>.<table> VALIDATE CONSTRAINT <name>;
-- The query that lists the orphans row by row, for V156's tables and these,
-- is docs/runbooks/patient-fk-orphans.sql.
--
-- The constraint name is the one the JPA entity already declares in its
-- @ForeignKey (H2 builds those keys from the entities, so the tests have
-- always run with them); fk_<table>_patient where the entity names none.
--
-- 31 of the 36. Deliberately NOT constrained, each for a recorded reason:
--   * support.audit_event_logs  -- V141: an audit row outlives its subject.
--   * clinical.roi_requests     -- V151: disclosure accounting, the V141 call.
--   * empi.master_identities    -- spans identities across tenants by design.
--   * public.patient_insurances_v2, public.patient_medical_histories_v2 --
--     the legacy patients_v2 stack, dropped by V170 in this same release.
--
-- Idempotent: a table that already has ANY foreign key on patient_id to
-- clinical.patients is skipped, as is a table that does not exist here.
-- No automated rollback (DROP CONSTRAINT by name if ever needed).

DO $$
DECLARE
    spec          TEXT;
    specs         TEXT[] := ARRAY[
        'billing.billing_invoices:fk_bi_patient',
        'clinical.advance_directives:fk_directive_patient',
        'clinical.birth_plans:fk_birth_plans_patient',
        'clinical.discharge_approvals:fk_discharge_patient',
        'clinical.discharge_summaries:fk_discharge_summary_patient',
        'clinical.encounter_notes:fk_encounter_note_patient',
        'clinical.high_risk_pregnancy_plans:fk_high_risk_plans_patient',
        'clinical.maternal_history:fk_maternal_history_patient',
        'clinical.newborn_assessments:fk_newborn_assessment_patient',
        'clinical.nursing_notes:fk_nursing_notes_patient',
        'clinical.obgyn_referrals:fk_obgyn_referral_patient',
        'clinical.patient_chart_updates:fk_chart_update_patient',
        'clinical.patient_consents:fk_consent_patient',
        'clinical.patient_education_progress:fk_patient_education_progress_patient',
        'clinical.patient_education_questions:fk_patient_education_questions_patient',
        'clinical.patient_family_history:fk_family_history_patient',
        'clinical.patient_immunization:fk_immunization_patient',
        'clinical.patient_insurances:fk_pi_patient',
        'clinical.patient_primary_care:fk_patient_primary_care_patient',
        'clinical.patient_problem_history:fk_patient_problem_history_patient',
        'clinical.patient_social_history:fk_social_history_patient',
        'clinical.patient_surgical_history:fk_surgical_history_patient',
        'clinical.pharmacy_fills:fk_pharmacy_fill_patient',
        'clinical.postpartum_care_plans:fk_postpartum_plan_patient',
        'clinical.postpartum_observations:fk_postpartum_obs_patient',
        'clinical.procedure_orders:fk_procedure_order_patient',
        'clinical.refill_requests:fk_refill_patient',
        'clinical.treatment_plans:fk_tp_patient',
        'clinical.ultrasound_orders:fk_ultrasound_orders_patient',
        'clinical.visit_education_documentation:fk_visit_education_documentation_patient',
        'public.general_referrals:fk_general_referrals_patient'
    ];
    target        TEXT;
    schema_name   TEXT;
    table_name    TEXT;
    fk_name       TEXT;
    orphan_count  BIGINT;
    orphan_total  BIGINT := 0;
    added         INT := 0;
    skipped       INT := 0;
BEGIN
    FOREACH spec IN ARRAY specs LOOP
        target      := split_part(spec, ':', 1);
        fk_name     := split_part(spec, ':', 2);
        schema_name := split_part(target, '.', 1);
        table_name  := split_part(target, '.', 2);

        IF to_regclass(target) IS NULL THEN
            RAISE NOTICE 'V169: % does not exist here — skipped.', target;
            skipped := skipped + 1;
            CONTINUE;
        END IF;

        IF EXISTS (
            SELECT 1
              FROM pg_constraint c
              JOIN pg_class      t  ON t.oid  = c.conrelid
              JOIN pg_namespace  n  ON n.oid  = t.relnamespace
              JOIN pg_class      rt ON rt.oid = c.confrelid
              JOIN pg_namespace  rn ON rn.oid = rt.relnamespace
              JOIN pg_attribute  a  ON a.attrelid = t.oid AND a.attnum = ANY (c.conkey)
             WHERE c.contype  = 'f'
               AND n.nspname  = schema_name
               AND t.relname  = table_name
               AND a.attname  = 'patient_id'
               AND rn.nspname = 'clinical'
               AND rt.relname = 'patients'
        ) THEN
            RAISE NOTICE 'V169: % already has a patient foreign key — nothing to do.', target;
            skipped := skipped + 1;
            CONTINUE;
        END IF;

        -- Report what is already broken before constraining the table, so the
        -- deploy log names the tables a human has to reconcile. Counted only:
        -- no row is deleted or rewritten here.
        EXECUTE format(
            'SELECT count(*) FROM %I.%I x '
            ' WHERE x.patient_id IS NOT NULL '
            '   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)',
            schema_name, table_name)
        INTO orphan_count;

        IF orphan_count > 0 THEN
            orphan_total := orphan_total + orphan_count;
            RAISE WARNING 'V169: % row(s) in % reference a patient that no longer exists. '
                          'The constraint is added NOT VALID so they do not fail this deploy; '
                          'list them with docs/runbooks/patient-fk-orphans.sql and reconcile '
                          'before VALIDATE CONSTRAINT %.',
                          orphan_count, target, fk_name;
        END IF;

        EXECUTE format(
            'ALTER TABLE %I.%I ADD CONSTRAINT %I '
            'FOREIGN KEY (patient_id) REFERENCES clinical.patients (id) NOT VALID',
            schema_name, table_name, fk_name);

        added := added + 1;
    END LOOP;

    RAISE NOTICE 'V169: done — % constraint(s) added (NO ACTION, NOT VALID), % skipped, % orphaned row(s) reported.',
        added, skipped, orphan_total;
END $$;
