-- =============================================================
-- V8: Fix missing unique constraint on staff.user_id
--     and normalize legacy REGISTERED stay_status values
--
-- 1. The JPA @OneToOne(unique=true) on Staff.user was never
--    reflected as a DB UNIQUE constraint; this caused Hibernate
--    "More than one row with the given identifier" errors when
--    two staff rows happened to share a user_id in dirty data.
--    Live DB confirmed 6 user_ids with duplicate rows (3 users
--    have 3 rows each, 2 users have 2 rows each).
--
--    The deduplication strategy:
--      a. Null out any head_of_department_staff_id FK in
--         hospital.departments that points to a staff row that
--         will be deleted (non-canonical duplicate), to avoid
--         FK violation during DELETE.
--      b. Keep the row with the latest created_at (most recent
--         activity) for each user_id — the newest row is the one
--         most likely to be the "current" staff record.
--      c. Re-point head_of_department_staff_id to the surviving
--         (canonical) staff id for each department whose pointer
--         was nulled.
--      d. Delete the remaining non-canonical duplicates.
--      e. Add UNIQUE INDEX on staff.user_id.
--
-- 2. Some patient_hospital_registrations rows contain
--    stay_status = 'REGISTERED' from legacy seed data.
--
-- 3. Normalize legacy job_title LAB_SCIENTIST → LABORATORY_SCIENTIST.
-- =============================================================

-- -----------------------------------------------------------------
-- 1a. Identify canonical staff ids (latest created_at per user_id)
--     and store in a temp table for use in subsequent steps.
-- -----------------------------------------------------------------
CREATE TEMP TABLE IF NOT EXISTS _staff_canonical AS
SELECT DISTINCT ON (user_id)
       id        AS canonical_id,
       user_id
FROM   hospital.staff
ORDER  BY user_id, created_at DESC, id DESC;

-- -----------------------------------------------------------------
-- 1b. Re-point department head FKs that point at a duplicate
--     (non-canonical) staff row → point them at the canonical row.
-- -----------------------------------------------------------------
UPDATE hospital.departments d
SET    head_of_department_staff_id = c.canonical_id
FROM   _staff_canonical c
WHERE  d.head_of_department_staff_id IS NOT NULL
  AND  d.head_of_department_staff_id NOT IN (SELECT canonical_id FROM _staff_canonical)
  AND  EXISTS (
           SELECT 1
           FROM   hospital.staff s
           WHERE  s.id = d.head_of_department_staff_id
             AND  s.user_id = c.user_id
       );

-- -----------------------------------------------------------------
-- 1c. Null out any remaining head_of_department_staff_id that still
--     points at a to-be-deleted staff row (safety net).
-- -----------------------------------------------------------------
UPDATE hospital.departments
SET    head_of_department_staff_id = NULL
WHERE  head_of_department_staff_id IS NOT NULL
  AND  head_of_department_staff_id NOT IN (SELECT canonical_id FROM _staff_canonical);

-- -----------------------------------------------------------------
-- 1d. Delete non-canonical duplicate staff rows.
--     Keep only the canonical (latest) row per user_id.
-- -----------------------------------------------------------------
DELETE FROM hospital.staff
WHERE id NOT IN (SELECT canonical_id FROM _staff_canonical);

DROP TABLE IF EXISTS _staff_canonical;

-- -----------------------------------------------------------------
-- 2. Add unique index on staff.user_id (matches @OneToOne unique=true)
-- -----------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS uq_staff_user_id
    ON hospital.staff (user_id);

-- -----------------------------------------------------------------
-- 3. Normalize legacy REGISTERED → ADMITTED in patient registrations
--    (REGISTERED is retained in the Java enum for read-compatibility
--    with any other data sources, but new rows should use ADMITTED)
-- -----------------------------------------------------------------
UPDATE clinical.patient_hospital_registrations
SET    stay_status = 'ADMITTED'
WHERE  stay_status = 'REGISTERED';

-- -----------------------------------------------------------------
-- 4. Normalize legacy job_title value LAB_SCIENTIST → LABORATORY_SCIENTIST
--    The Java enum was extended with LAB_SCIENTIST for read-compatibility,
--    but new staff records should use the canonical LABORATORY_SCIENTIST value.
-- -----------------------------------------------------------------
UPDATE hospital.staff
SET    job_title = 'LABORATORY_SCIENTIST'
WHERE  job_title = 'LAB_SCIENTIST';
