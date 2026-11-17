-- V235 (QA D786): support.audit_event_logs.hospital_id, the hospital a row
-- belongs to, so a SYSTEM row (no user, no assignment) written for one
-- hospital's data reaches that hospital's own audit view. Before this, the
-- hospital-scoped reads anchored rows only through assignment_id, so the two
-- V229 cancellation rows were visible to the platform admin and never to the
-- admin of the hospital whose invoices were cancelled.
--
-- New rows: the application stamps hospital_id from the assignment's hospital,
-- and the audit service sets it for a SYSTEM row that names its hospital.
-- A bare UUID with no foreign key, like patient_id (V141): an audit row
-- outlives what it describes.
--
-- Backfill, deliberately narrow: the two kinds of SYSTEM row known to act on
-- one hospital's data, each from the row it targets (never from the
-- hospital_name snapshot, since names are not keys):
--   * the V229 cancellations, from billing_invoices.hospital_id,
--   * the care-gap sweep's tracing recalls (PROGRAM_DEFAULTER_TRACED), from
--     scheduling.patient_recalls.hospital_id.
-- Rows with an assignment need no backfill: the reads still anchor through
-- it. Ids only, no PHI. Idempotent (IF NOT EXISTS, and each UPDATE only
-- touches rows whose hospital_id is still NULL).
-- Plain statements, no DO block.

ALTER TABLE support.audit_event_logs
    ADD COLUMN IF NOT EXISTS hospital_id UUID;

CREATE INDEX IF NOT EXISTS idx_audit_hospital_time
    ON support.audit_event_logs (hospital_id, event_timestamp);

UPDATE support.audit_event_logs a
   SET hospital_id = bi.hospital_id
  FROM billing.billing_invoices bi
 WHERE a.hospital_id IS NULL
   AND a.user_id IS NULL
   AND a.assignment_id IS NULL
   AND a.actor_type = 'SYSTEM'
   AND a.actor_label = 'Migration V229'
   AND a.target_entity_type = 'BillingInvoice'
   AND a.target_resource_id = bi.id::text;

UPDATE support.audit_event_logs a
   SET hospital_id = r.hospital_id
  FROM scheduling.patient_recalls r
 WHERE a.hospital_id IS NULL
   AND a.user_id IS NULL
   AND a.assignment_id IS NULL
   AND a.actor_type = 'SYSTEM'
   AND a.event_type = 'PROGRAM_DEFAULTER_TRACED'
   AND a.target_entity_type = 'PatientRecall'
   AND a.target_resource_id = r.id::text;
