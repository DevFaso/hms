-- V244 (round 17, front desk and identity).
--
-- QA D1014: approving a nurse-requested discharge ended the patient's
-- REGISTRATION at the hospital (is_active = false) instead of only the stay
-- (stay_status = DISCHARGED). Every patient list, search and picker reads
-- active registrations only, so the patient vanished everywhere and could not
-- be readmitted, booked or seen again, while the other discharge path (the
-- admission discharge) kept the registration active. A discharge ends a stay,
-- not a registration; the code no longer clears the flag.
--
-- This puts back the registrations the discharge approval ended: discharged,
-- inactive, and not changed since the discharge stamped stay_status_updated_at
-- (a registration an administrator deactivated later has a later updated_at
-- and is left alone).
--
-- Data only, no schema change, no PHI read or written (two flags on rows
-- selected by status and timestamps). Plain statement, no DO block.
-- Idempotent: a second run matches no row.

UPDATE clinical.patient_hospital_registrations
   SET is_active = TRUE
 WHERE is_active = FALSE
   AND stay_status = 'DISCHARGED'
   AND stay_status_updated_at IS NOT NULL
   AND (updated_at IS NULL OR updated_at <= stay_status_updated_at + INTERVAL '5 seconds');
