-- QA D819: operator report, read-only. Lists every patient record whose login
-- account is a staff account: the account has a staff record, a role
-- assignment other than PATIENT (active or not), or a global role other than
-- PATIENT. Before the fix, POST /patients accepted any userId, so a staff
-- login could become a patient's portal login. Each row is a decision for the
-- operator (relink the record to a new patient account, or delete it); this
-- query changes nothing. Identifiers only: no name, e-mail or phone.
SELECT p.id                                         AS patient_id,
       p.hospital_id                                AS patient_hospital_id,
       u.id                                         AS user_id,
       EXISTS (SELECT 1 FROM hospital.staff s WHERE s.user_id = u.id)
                                                    AS has_staff_record,
       (SELECT string_agg(DISTINCT r.code, ',' ORDER BY r.code)
          FROM "security".user_role_hospital_assignment a
          JOIN "security".roles r ON r.id = a.role_id
         WHERE a.user_id = u.id
           AND upper(r.code) NOT IN ('ROLE_PATIENT', 'PATIENT')) AS staff_assignment_roles,
       (SELECT string_agg(DISTINCT r.code, ',' ORDER BY r.code)
          FROM "security".user_roles ur
          JOIN "security".roles r ON r.id = ur.role_id
         WHERE ur.user_id = u.id
           AND upper(r.code) NOT IN ('ROLE_PATIENT', 'PATIENT')) AS staff_global_roles,
       p.created_at                                 AS patient_created_at
FROM clinical.patients p
JOIN "security".users u ON u.id = p.user_id
WHERE EXISTS (SELECT 1 FROM hospital.staff s WHERE s.user_id = u.id)
   OR EXISTS (SELECT 1
                FROM "security".user_role_hospital_assignment a
                JOIN "security".roles r ON r.id = a.role_id
               WHERE a.user_id = u.id
                 AND upper(r.code) NOT IN ('ROLE_PATIENT', 'PATIENT'))
   OR EXISTS (SELECT 1
                FROM "security".user_roles ur
                JOIN "security".roles r ON r.id = ur.role_id
               WHERE ur.user_id = u.id
                 AND upper(r.code) NOT IN ('ROLE_PATIENT', 'PATIENT'))
ORDER BY p.created_at, p.id;
