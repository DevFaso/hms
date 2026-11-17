-- V191: lab test definitions - repair the hospital scope and grandfather the
-- orderable status (QA run 2026-10-17, defects D30 and D48).
--
-- D30. LabTestDefinitionServiceImpl.create and .update set hospital_id to NULL
-- on every save, whatever the caller sent, so a definition a hospital's lab
-- authored became a GLOBAL row that every other tenant read, and its own author
-- then got 403 on its reference ranges (global rows are super-admin only). The
-- service now files a definition under the hospital it was authored at. This
-- repairs the rows already written: a NULL-hospital row whose owning
-- assignment is at a hospital and is NOT a super-admin assignment goes back to
-- that hospital. Rows a super-admin authored, and rows with no assignment
-- (seeded catalog), stay global - that is the platform catalog.
--
-- D48. Only an ACTIVE definition may now be ordered. Before this change the
-- order path ignored the approval workflow, so every row with is_active = TRUE
-- has been orderable whatever its approval_status (V30 marked the rows of its
-- day APPROVED; rows created since default to DRAFT). Turning the rule on
-- without this step would make most existing catalogs unorderable at deploy.
-- So the rows that are switched on and sit in DRAFT or APPROVED are recorded as
-- ACTIVE: exactly the set that was orderable yesterday stays orderable. Rows in
-- review (PENDING_*), REJECTED or RETIRED, and every row created from now on,
-- follow the workflow. A laboratory that wants a grandfathered row reviewed
-- again retires it and authors a new one.
--
-- Idempotent: both UPDATEs only match rows still in the state they repair.
-- No DO block, no schema change.

UPDATE lab.lab_test_definitions d
   SET hospital_id = a.hospital_id
  FROM security.user_role_hospital_assignment a
  JOIN security.roles r ON r.id = a.role_id
 WHERE d.assignment_id = a.id
   AND d.hospital_id IS NULL
   AND a.hospital_id IS NOT NULL
   AND UPPER(r.code) NOT IN ('ROLE_SUPER_ADMIN', 'SUPER_ADMIN');

UPDATE lab.lab_test_definitions
   SET approval_status = 'ACTIVE'
 WHERE is_active = TRUE
   AND approval_status IN ('DRAFT', 'APPROVED');
