-- Migration V6: repair users that are soft-deleted (is_deleted = true) but still have
-- active role assignments.  This situation arises when an admin registers a user,
-- assigns them a role, and then soft-deletes the account — leaving a "ghost" user who
-- can receive JWT tokens (their UserRoleHospitalAssignment is active) but is invisible
-- in every list query that filters `is_deleted = false`.
--
-- Strategy: any user who is currently soft-deleted but owns at least one ACTIVE
-- UserRoleHospitalAssignment is considered "accidentally deleted" and is restored.
-- Their is_deleted flag is cleared and is_active is set to true.
--
-- This is safe to run multiple times (idempotent by design: the WHERE clause only
-- matches deleted rows, so already-restored users are unaffected).

UPDATE security.users u
SET    is_deleted = false,
       is_active  = true,
       updated_at = now()
WHERE  u.is_deleted = true
  AND  EXISTS (
           SELECT 1
           FROM   security.user_role_hospital_assignment a
           WHERE  a.user_id = u.id
             AND  a.active  = true
       );
