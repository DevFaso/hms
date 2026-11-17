-- V175: the two assignment foreign keys the entities have always declared.
--
-- WHY: Encounter.assignment and Staff.assignment are mapped with
-- @ForeignKey(name = "fk_encounter_assignment") and
-- @ForeignKey(name = "fk_staff_assignment"), and H2 builds both keys from the
-- entities, so every test has always run with them -- but no migration ever
-- created them on Postgres. Meanwhile removing a user hard-deleted their
-- assignments, so a departed clinician's encounters and staff rows were left
-- pointing at nothing: on dev (2026-09-13) four Hospital B encounters could
-- no longer be edited by anyone. The schema and the model disagreed, and
-- nothing noticed.
--
-- The decision this records: assignments are SOFT-deleted. Removing a user
-- now deactivates their assignments instead of deleting them
-- (UserRoleHospitalAssignmentServiceImpl.deleteAllAssignmentsForUser), and
-- the deprecated single-assignment hard delete refuses an assignment that a
-- staff row or an encounter still references. These keys are the backstop
-- that makes that true for any caller.
--
-- WHAT THIS DOES: for clinical.encounters and hospital.staff, counts the rows
-- whose assignment_id names no assignment (WARNING, ids not logged), then
-- adds the key with the entity's name, NO ACTION, NOT VALID -- the V115/V156
-- precedent: the rows that already dangle stay exactly as they are and do not
-- fail the deploy; new and updated rows, and deletes of a referenced
-- assignment, ARE checked. Nothing is backfilled, because nothing records
-- which assignment a dangling row should have pointed at; reconcile them by
-- hand, then VALIDATE CONSTRAINT.
--
-- Not constrained here, and recorded: the other eighteen tables that carry an
-- assignment_id (audit_event_logs by design since #564; lab results, orders,
-- prescriptions, appointments and the rest). With assignments no longer
-- deleted when a user leaves, they stop acquiring new dangling ids from that
-- path; constraining them is a judgement per table.
--
-- Idempotent (constraint existence checked). Postgres only. Rollback:
-- ALTER TABLE ... DROP CONSTRAINT fk_encounter_assignment / fk_staff_assignment.

DO $$
DECLARE
    spec      TEXT;
    specs     TEXT[] := ARRAY[
        'clinical.encounters:fk_encounter_assignment',
        'hospital.staff:fk_staff_assignment'
    ];
    target    TEXT;
    sch       TEXT;
    tbl       TEXT;
    fk_name   TEXT;
    dangling  BIGINT;
BEGIN
    FOREACH spec IN ARRAY specs LOOP
        target  := split_part(spec, ':', 1);
        fk_name := split_part(spec, ':', 2);
        sch     := split_part(target, '.', 1);
        tbl     := split_part(target, '.', 2);

        IF to_regclass(target) IS NULL THEN
            RAISE NOTICE 'V175: % does not exist here — skipped.', target;
            CONTINUE;
        END IF;

        IF EXISTS (
            SELECT 1
              FROM pg_constraint c
              JOIN pg_class      t ON t.oid = c.conrelid
              JOIN pg_namespace  n ON n.oid = t.relnamespace
             WHERE c.conname = fk_name
               AND n.nspname = sch
               AND t.relname = tbl
        ) THEN
            RAISE NOTICE 'V175: % already present — nothing to do.', fk_name;
            CONTINUE;
        END IF;

        EXECUTE format(
            'SELECT count(*) FROM %I.%I x '
            ' WHERE x.assignment_id IS NOT NULL '
            '   AND NOT EXISTS (SELECT 1 FROM security.user_role_hospital_assignment a '
            '                    WHERE a.id = x.assignment_id)',
            sch, tbl)
        INTO dangling;

        IF dangling > 0 THEN
            RAISE WARNING 'V175: % row(s) in % reference an assignment that no longer exists. '
                          'The key is added NOT VALID so they do not fail this deploy; reconcile them, '
                          'then ALTER TABLE % VALIDATE CONSTRAINT %.',
                          dangling, target, target, fk_name;
        END IF;

        EXECUTE format(
            'ALTER TABLE %I.%I ADD CONSTRAINT %I '
            'FOREIGN KEY (assignment_id) REFERENCES security.user_role_hospital_assignment (id) NOT VALID',
            sch, tbl, fk_name);

        RAISE NOTICE 'V175: % added (NO ACTION, NOT VALID).', fk_name;
    END LOOP;
END $$;
