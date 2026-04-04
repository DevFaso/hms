-- ============================================================
-- V33: Consent type/scope columns + audit performance indexes
-- ============================================================

-- 1. Add consent_type to patient_consents
--    Default to TREATMENT for existing rows (safe, non-breaking)
ALTER TABLE clinical.patient_consents
    ADD COLUMN IF NOT EXISTS consent_type VARCHAR(50) NOT NULL DEFAULT 'TREATMENT';

-- 2. Add scope: comma-separated list of record domains shared
--    e.g. 'PRESCRIPTIONS,LAB_RESULTS,ENCOUNTERS'
--    NULL = all domains (unrestricted)
ALTER TABLE clinical.patient_consents
    ADD COLUMN IF NOT EXISTS scope VARCHAR(500);

-- 3. Performance index for date-range queries on audit_event_logs
CREATE INDEX IF NOT EXISTS idx_audit_event_timestamp
    ON support.audit_event_logs(event_timestamp DESC);

-- 4. Index for hospital-scoped audit queries (already used by dashboard queries)
CREATE INDEX IF NOT EXISTS idx_audit_assignment
    ON support.audit_event_logs(assignment_id)
    WHERE assignment_id IS NOT NULL;

-- 5. Index for consent type lookups
CREATE INDEX IF NOT EXISTS idx_consent_type
    ON clinical.patient_consents(consent_type);
