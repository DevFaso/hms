-- V192: lab test definitions - repair the hospital scope a second time
-- (QA run 2026-10-18, defect D73, a regression of D30).
--
-- V191 re-filed the definitions wrongly saved as global and the service was
-- changed to set hospital_id from the acting scope. But LabTestDefinition's
-- @PrePersist/@PreUpdate hook still set hospital = null on every insert and
-- update, so every definition created since still reached the database as a
-- global row, and every later save (an edit, each approval step, a reference
-- range update) re-globalised the rows V191 had repaired. The hook no longer
-- touches the hospital. This repeats V191's repair for the rows written
-- since: a NULL-hospital row whose owning assignment is at a hospital and is
-- NOT a super-admin assignment goes back to that hospital. Rows a super-admin
-- authored, and rows with no assignment, stay global (the platform catalog);
-- a row authored with no assignment cannot be attributed and is left as is.
--
-- Idempotent: the UPDATE only matches rows still in the state it repairs.
-- No DO block, no schema change.

UPDATE lab.lab_test_definitions d
   SET hospital_id = a.hospital_id
  FROM security.user_role_hospital_assignment a
  JOIN security.roles r ON r.id = a.role_id
 WHERE d.assignment_id = a.id
   AND d.hospital_id IS NULL
   AND a.hospital_id IS NOT NULL
   AND UPPER(r.code) NOT IN ('ROLE_SUPER_ADMIN', 'SUPER_ADMIN');
