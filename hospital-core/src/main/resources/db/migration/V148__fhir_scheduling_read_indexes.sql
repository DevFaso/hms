-- V148: read-path indexes for the FHIR Appointment + Slot providers
-- (Tier 2 item 43).
--
-- WHY: the two provider queries are new read shapes with no matching
-- composite index, and ddl-auto=validate means an @Index on the entity
-- creates nothing in production - a migration is the only thing that does.
--
--  * Appointment search reads (hospital, patient) newest-first; without an
--    index that is a filtered scan per patient chart pull.
--  * Slot search reads (hospital, date window) ordered by start; V121's
--    idx_slot_search_open cannot serve it - that index is PARTIAL on
--    status='OPEN' and interposes department_id, while this inventory view
--    spans every status over a growing slot history.
--
-- Rollback:
--   DROP INDEX clinical.idx_appt_fhir_patient_search;
--   DROP INDEX clinical.idx_slot_fhir_window;
-- =============================================================================

CREATE INDEX IF NOT EXISTS idx_appt_fhir_patient_search
    ON clinical.appointments (hospital_id, patient_id, appointment_date DESC, start_time DESC);

CREATE INDEX IF NOT EXISTS idx_slot_fhir_window
    ON clinical.appointment_slots (hospital_id, slot_date, start_at);
