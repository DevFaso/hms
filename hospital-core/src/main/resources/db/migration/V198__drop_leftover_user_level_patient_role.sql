-- V198: drop the leftover user-level ROLE_PATIENT on staff accounts (QA D74, D132).
--
-- A patient registration that re-used a staff account linked ROLE_PATIENT in
-- security.user_roles. Deleting the stray patient assignment later left that
-- link behind, and with no assignment row it was honoured as a legacy global
-- role: the receptionist saw none of the hospital's appointments, sign-in
-- asked for a role, and audit rows were filed under PATIENT.
--
-- Removed only where the account (a) has NO ROLE_PATIENT assignment at all,
-- active or not, and (b) actively holds another role somewhere. A real
-- patient always has a patient assignment, and a patient-only account is
-- left untouched.
DELETE FROM security.user_roles ur
USING security.roles r
WHERE ur.role_id = r.id
  AND upper(r.code) = 'ROLE_PATIENT'
  AND NOT EXISTS (
      SELECT 1
      FROM security.user_role_hospital_assignment a
      WHERE a.user_id = ur.user_id
        AND a.role_id = r.id
  )
  AND EXISTS (
      SELECT 1
      FROM security.user_role_hospital_assignment a
      JOIN security.roles other ON other.id = a.role_id
      WHERE a.user_id = ur.user_id
        AND a.active = TRUE
        AND upper(other.code) <> 'ROLE_PATIENT'
  );
