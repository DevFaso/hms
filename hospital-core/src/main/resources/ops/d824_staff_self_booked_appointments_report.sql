-- QA D824: operator report, read-only. Lists every appointment booked by the
-- patient's own login account when that account is a staff account (a staff
-- record, a role assignment other than PATIENT, active or not, or a global
-- role other than PATIENT). Before the fix, a staff POST /appointments with no
-- patientId fell back to the caller's own linked patient record, so a staff
-- member whose login had been linked to a patient (QA D819) booked that
-- patient without choosing anyone. Each row is a decision for the operator
-- (keep, move to the right patient, or cancel); this query changes nothing.
-- Identifiers only: no name, e-mail or phone.
SELECT a.id               AS appointment_id,
       a.hospital_id      AS hospital_id,
       a.patient_id       AS patient_id,
       a.created_by       AS booked_by_user_id,
       a.status           AS status,
       a.appointment_date AS appointment_date,
       a.created_at       AS booked_at
FROM clinical.appointments a
JOIN clinical.patients p ON p.id = a.patient_id
WHERE a.created_by IS NOT NULL
  AND a.created_by = p.user_id
  AND (EXISTS (SELECT 1 FROM hospital.staff s WHERE s.user_id = a.created_by)
       OR EXISTS (SELECT 1
                    FROM "security".user_role_hospital_assignment ura
                    JOIN "security".roles r ON r.id = ura.role_id
                   WHERE ura.user_id = a.created_by
                     AND upper(r.code) NOT IN ('ROLE_PATIENT', 'PATIENT'))
       OR EXISTS (SELECT 1
                    FROM "security".user_roles ur
                    JOIN "security".roles r ON r.id = ur.role_id
                   WHERE ur.user_id = a.created_by
                     AND upper(r.code) NOT IN ('ROLE_PATIENT', 'PATIENT')))
ORDER BY a.created_at, a.id;
