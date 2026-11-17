-- QA D304: a patient holds at most one open stay.
-- The service refuses a second open admission (409) under a patient row lock;
-- this partial unique index is the database backstop for any other writer.
-- It is created only when the existing data allows it: if duplicates already
-- exist, the index is skipped with a NOTICE and the duplicates are left for a
-- person to close (the application guard still stops new ones). Re-running the
-- service guard after cleanup is enough; a later migration can add the index.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.admissions
        WHERE status IN ('PENDING', 'ACTIVE', 'ON_LEAVE', 'AWAITING_DISCHARGE')
        GROUP BY patient_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE NOTICE 'V209: patients with more than one open admission exist; unique index ux_admissions_one_open_per_patient not created';
    ELSE
        EXECUTE 'CREATE UNIQUE INDEX IF NOT EXISTS ux_admissions_one_open_per_patient '
             || 'ON public.admissions (patient_id) '
             || 'WHERE status IN (''PENDING'', ''ACTIVE'', ''ON_LEAVE'', ''AWAITING_DISCHARGE'')';
    END IF;
END $$;
