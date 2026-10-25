-- V230 (QA D764): a patient registration is not a stay.
-- Every registration was created with stay_status = 'ADMITTED' (V8 even
-- folded the legacy 'REGISTERED' rows into it), so outpatients showed
-- "Admitted". From now on a registration starts REGISTERED and the
-- admission service moves it. Correct the existing rows: an ADMITTED
-- registration with no open admission of that patient at that hospital
-- becomes REGISTERED. Rows in any other status are left as they are.
UPDATE clinical.patient_hospital_registrations r
SET    stay_status = 'REGISTERED',
       stay_status_updated_at = NOW()
WHERE  r.stay_status = 'ADMITTED'
  AND  NOT EXISTS (
         SELECT 1
         FROM   public.admissions a
         WHERE  a.patient_id  = r.patient_id
           AND  a.hospital_id = r.hospital_id
           AND  a.status IN ('PENDING', 'ACTIVE', 'ON_LEAVE', 'AWAITING_DISCHARGE')
       );
