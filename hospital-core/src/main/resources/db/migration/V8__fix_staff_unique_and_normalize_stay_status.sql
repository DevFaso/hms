-- =============================================================
-- V8: Fix missing unique constraint on staff.user_id
--     and normalize legacy REGISTERED stay_status values
--
-- 1. The JPA @OneToOne(unique=true) on Staff.user was never
--    reflected as a DB UNIQUE constraint; this caused Hibernate
--    "More than one row with the given identifier" errors when
--    two staff rows happened to share a user_id in dirty data.
--    NOTE: if duplicate user_id rows exist, the deduplication
--    step below removes all but one before adding the constraint.
--
-- 2. Some patient_hospital_registrations rows contain
--    stay_status = 'REGISTERED' from legacy seed data.
--    The Java enum was extended to include REGISTERED so reads
--    succeed; this migration additionally normalises old rows to
--    ADMITTED so the DB reflects the canonical lifecycle.
-- =============================================================

-- -----------------------------------------------------------------
-- 1. De-duplicate hospital.staff rows sharing the same user_id
--    Keep the row with the earliest created_at for each user_id.
-- -----------------------------------------------------------------
DELETE FROM hospital.staff
WHERE id IN (
    SELECT id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY user_id
                   ORDER BY created_at ASC, id ASC
               ) AS rn
        FROM hospital.staff
    ) ranked
    WHERE rn > 1
);

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
