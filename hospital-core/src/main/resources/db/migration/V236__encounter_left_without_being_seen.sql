-- V236 (round 13, product decision): a visit closed as "left without being
-- seen" is its own encounter status, LEFT_WITHOUT_BEING_SEEN, distinct from a
-- visit the staff cancelled. Since QA D788 the close-unseen endpoint wrote
-- status CANCELLED for both outcomes and kept the outcome in the extra fields
-- ({"unseenOutcome":"LEFT_WITHOUT_BEING_SEEN",...}, compact Jackson JSON). Those
-- rows move to the new status; a close-unseen with outcome CANCELLED, and every
-- other cancelled visit, stays CANCELLED. A no-show stays on the appointment.
--
-- QA D804: a stay's encounter kept the department the patient was admitted
-- to after a transfer (the admission moved, the encounter did not), so the
-- encounter list contradicted the after-visit summary. The application now
-- moves the stay encounter with the admission; existing stay encounters take
-- their admission's current department, only when that department belongs
-- to the encounter's own hospital.
--
-- Ids and status codes only, no PHI. Plain statements, no DO block.
-- Idempotent: each UPDATE only touches rows not already in the target state.

-- The status column is a plain VARCHAR(60) since V1 (no CHECK). A database
-- that ever had Hibernate generate the enum CHECK would refuse the new value,
-- so drop it if, and only if, it is there.
ALTER TABLE clinical.encounters DROP CONSTRAINT IF EXISTS encounters_status_check;

UPDATE clinical.encounters
   SET status = 'LEFT_WITHOUT_BEING_SEEN'
 WHERE status = 'CANCELLED'
   AND extra_fields LIKE '%"unseenOutcome":"LEFT\_WITHOUT\_BEING\_SEEN"%' ESCAPE '\';

UPDATE clinical.encounters e
   SET department_id = a.department_id
  FROM public.admissions a
  JOIN hospital.departments d ON d.id = a.department_id
 WHERE e.admission_id = a.id
   AND d.hospital_id = e.hospital_id
   AND e.department_id IS DISTINCT FROM a.department_id;
