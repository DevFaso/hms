-- =====================================================================
-- V96: De-duplicate user_role_hospital_assignment and prevent regression
--
-- Context:
--   security.user_role_hospital_assignment was created in V1 with PK (id)
--   only — no unique constraint on (user_id, hospital_id, role_id).
--   DevSyntheticDataSeeder.createAssignment used to insert unconditionally
--   on every boot, accumulating duplicate (user, hospital, role) rows in
--   dev. The seeder is now idempotent (see paired Java change in this
--   PR), but the schema must enforce the invariant too so the regression
--   can't slip back in via any future insert path.
--
-- Steps:
--   1. Delete dependent permission rows belonging to duplicate
--      assignments (permissions.assignment_id has no DB-level FK so we
--      delete them explicitly; otherwise step 2 would orphan them).
--   2. Delete the duplicate assignment rows, keeping the oldest per
--      (user_id, hospital_id, role_id) group (smallest created_at,
--      smallest id as tiebreaker for NULL created_at rows).
--   3. Add partial unique indexes that catch both hospital-scoped and
--      global (hospital_id IS NULL) duplicates. Partial indexes are used
--      instead of NULLS NOT DISTINCT so this migration works on any
--      Postgres version ≥ 9.2 (NULLS NOT DISTINCT requires PG15+).
--
-- Idempotency: the WHERE rn > 1 clauses are no-ops once data is clean,
--   and CREATE UNIQUE INDEX IF NOT EXISTS won't recreate existing
--   indexes. Re-running this migration is safe.
-- =====================================================================

-- Step 1: delete permissions belonging to duplicate assignments
DELETE FROM security.permissions
WHERE assignment_id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY user_id, hospital_id, role_id
                   ORDER BY created_at NULLS LAST, id
               ) AS rn
        FROM security.user_role_hospital_assignment
    ) ranked
    WHERE rn > 1
);

-- Step 2: delete the duplicate assignments themselves
DELETE FROM security.user_role_hospital_assignment
WHERE id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY user_id, hospital_id, role_id
                   ORDER BY created_at NULLS LAST, id
               ) AS rn
        FROM security.user_role_hospital_assignment
    ) ranked
    WHERE rn > 1
);

-- Step 3a: prevent hospital-scoped (user, hospital, role) duplicates
CREATE UNIQUE INDEX IF NOT EXISTS uk_urha_user_role_hospital
    ON security.user_role_hospital_assignment (user_id, role_id, hospital_id)
    WHERE hospital_id IS NOT NULL;

-- Step 3b: prevent global (user, role) duplicates where hospital_id IS NULL
CREATE UNIQUE INDEX IF NOT EXISTS uk_urha_user_role_global
    ON security.user_role_hospital_assignment (user_id, role_id)
    WHERE hospital_id IS NULL;
