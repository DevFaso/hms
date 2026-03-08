-- =============================================================
-- V10: Activate hospital-scoped staff assignments created by admin
--
-- Bug: enforceRoleScopeConstraints() forced is_active=false for
-- ALL staff role assignments (confirmation-code workflow). This
-- meant admin-registered staff never got primaryHospitalId in
-- their JWT, breaking /api/me/hospital and patient registration.
--
-- Fix (application code): ensureAssignmentSmart() now re-activates
-- assignments when the admin-register flow requests active=true.
--
-- This migration repairs existing data: activates hospital-scoped
-- non-patient, non-super-admin assignments that were created in
-- the same transaction as their user (created_at ≈ user created_at,
-- within 5 seconds) — these are the admin-register rows.
-- Intentionally deactivated assignments (where updated_at differs
-- significantly from created_at) are left untouched.
-- =============================================================

UPDATE security.user_role_hospital_assignment a
SET    is_active   = true,
       updated_at  = NOW()
WHERE  a.is_active = false
  AND  a.hospital_id IS NOT NULL
  AND  a.role_id NOT IN (
           SELECT r.id FROM security.roles r
           WHERE  r.code IN ('ROLE_PATIENT', 'ROLE_SUPER_ADMIN')
       )
  -- Only target rows that were never intentionally deactivated:
  -- admin-register creates the assignment in the same transaction, so
  -- created_at ≈ updated_at (within 5 seconds). If updated_at diverges
  -- significantly, the row was deliberately deactivated later.
  AND  a.updated_at <= a.created_at + INTERVAL '5 seconds';
