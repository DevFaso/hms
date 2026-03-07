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
-- This migration repairs existing data: activates all hospital-
-- scoped non-patient, non-super-admin assignments that are
-- currently inactive (these were all created by admin-register).
-- =============================================================

UPDATE security.user_role_hospital_assignment a
SET    is_active   = true,
       updated_at  = NOW()
WHERE  a.is_active = false
  AND  a.hospital_id IS NOT NULL
  AND  a.role_id NOT IN (
           SELECT r.id FROM security.roles r
           WHERE  r.code IN ('ROLE_PATIENT', 'ROLE_SUPER_ADMIN')
       );
