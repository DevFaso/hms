-- ─────────────────────────────────────────────────────────────────────────────
-- MVP 12: Nurse Station Cockpit — Phase 2
-- No new tables.  Adds composite indexes that support the four new
-- workboard / flow-board / vitals endpoints introduced in this MVP.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── 1. Admissions: composite index for active-admissions-by-hospital lookup ──
-- Supports NurseTaskServiceImpl.getWorkboard() and getPatientFlow() which
-- filter on (hospital_id, status).  The table already has single-column
-- indexes on hospital_id and status individually; the composite is faster
-- for the combined WHERE clause used in those queries.
CREATE INDEX IF NOT EXISTS idx_admissions_hospital_status
    ON admissions (hospital_id, status);

-- ── 2. Patient Vital Signs: composite index for latest-vitals lookup ──────────
-- Supports NurseTaskServiceImpl.getWorkboard():
--   findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(patientId, hospitalId)
-- The DESC on recorded_at lets Postgres satisfy the ORDER BY with an
-- index scan rather than a sort.
CREATE INDEX IF NOT EXISTS idx_pvs_patient_hospital_recorded
    ON clinical.patient_vital_signs (patient_id, hospital_id, recorded_at DESC);
