-- Patient foreign-key orphans: rows whose patient_id names no clinical.patients row.
--
-- READ ONLY. Lists every orphan on the tables V156 (10) and V169 (31)
-- constrained NOT VALID, one row per orphan: the table, the row id, the
-- missing patient id and when the row was created. Ids only -- no clinical
-- text, no names -- so the output can be pasted into a ticket.
--
-- Nothing here deletes or rewrites anything. Each orphan needs a human
-- decision (reconcile to the right patient, or remove the row through the
-- application with its audit trail); then close the table for good with
--     ALTER TABLE <table> VALIDATE CONSTRAINT <fk name>;
-- The FK names are in V156__patient_fk_integrity.sql and
-- V169__patient_fk_integrity_remaining.sql.
--
-- Usage: psql "$DATABASE_URL" -f docs/runbooks/patient-fk-orphans.sql
-- Summary only: wrap the query in  SELECT table_name, count(*) FROM (...) o GROUP BY 1.

SELECT 'clinical.consultations' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.consultations x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'public.admissions' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM public.admissions x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.encounters' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.encounters x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.prescriptions' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.prescriptions x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.patient_vital_signs' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.patient_vital_signs x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.patient_allergies' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.patient_allergies x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.patient_problems' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.patient_problems x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.imaging_orders' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.imaging_orders x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.appointments' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.appointments x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'lab.lab_orders' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM lab.lab_orders x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'billing.billing_invoices' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM billing.billing_invoices x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.advance_directives' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.advance_directives x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.birth_plans' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.birth_plans x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.discharge_approvals' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.discharge_approvals x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.discharge_summaries' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.discharge_summaries x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.encounter_notes' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.encounter_notes x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.high_risk_pregnancy_plans' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.high_risk_pregnancy_plans x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.maternal_history' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.maternal_history x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.newborn_assessments' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.newborn_assessments x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.nursing_notes' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.nursing_notes x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.obgyn_referrals' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.obgyn_referrals x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.patient_chart_updates' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.patient_chart_updates x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.patient_consents' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.patient_consents x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.patient_education_progress' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.patient_education_progress x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.patient_education_questions' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.patient_education_questions x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.patient_family_history' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.patient_family_history x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.patient_immunization' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.patient_immunization x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.patient_insurances' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.patient_insurances x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.patient_primary_care' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.patient_primary_care x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.patient_problem_history' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.patient_problem_history x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.patient_social_history' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.patient_social_history x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.patient_surgical_history' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.patient_surgical_history x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.pharmacy_fills' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.pharmacy_fills x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.postpartum_care_plans' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.postpartum_care_plans x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.postpartum_observations' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.postpartum_observations x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.procedure_orders' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.procedure_orders x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.refill_requests' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.refill_requests x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.treatment_plans' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.treatment_plans x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.ultrasound_orders' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.ultrasound_orders x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'clinical.visit_education_documentation' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM clinical.visit_education_documentation x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
UNION ALL
SELECT 'public.general_referrals' AS table_name, x.id AS row_id, x.patient_id AS missing_patient_id, x.created_at
  FROM public.general_referrals x
 WHERE x.patient_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM clinical.patients p WHERE p.id = x.patient_id)
ORDER BY table_name, created_at;
