-- =====================================================================
-- V99: ADT external-visit reconciliation columns on admissions + encounters
-- (roadmap row 24, v1.1 / Interop HL7 / "ADT^A01/A04/A08 → Admission/Encounter sync")
--
-- HL7 v2 ADT messages identify an inpatient stay / outpatient visit by
-- PV1-19 "Visit Number". That value is opaque to HMS — it is minted by
-- the sending system (admit/discharge/transfer module, registration
-- desk, lab order-entry, etc.) and is the only stable handle the
-- analyzer / sender will reuse on subsequent A04 (registration) and
-- A08 (update patient info) trigger events.
--
-- For the projection layer to reconcile an inbound ADT to an existing
-- HMS Admission or Encounter, we need to remember:
--   - external_visit_number          PV1-19, first component
--   - external_sending_application   MSH-3
--   - external_sending_facility      MSH-4
--   - external_message_control_id    MSH-10 of the message that LAST
--                                    updated this row (newest wins).
--
-- HL7 v2 only guarantees PV1-19 uniqueness WITHIN a sending system, so
-- the dedup key for "is this the same visit?" must be the composite
-- (sending application, sending facility, visit number, hospital). Two
-- different sending systems can legitimately reuse the same visit
-- number; those must NOT collapse into a single Admission/Encounter.
--
-- Columns are nullable + optional. All existing rows (every Admission
-- and Encounter currently in the database was created through the
-- in-app workflow, not via ADT) have NULL external_visit_number and
-- are excluded from the partial unique index. New manually-created
-- rows likewise leave the column NULL and are unaffected.
--
-- Strictly additive: every column nullable, every index IF NOT EXISTS.
-- No automated rollback is declared. Operators who need to revert
-- must drop the columns + indexes manually and redeploy a JPA mapping
-- that omits the fields in the same release.
--
-- Activation: the projection service that writes into these columns
-- is gated by app.hl7.adt.visit-sync.enabled (default false). Adding
-- the columns is a no-op for installations that never enable the
-- flag — the storage cost is one NULL bitmap entry per row.
-- =====================================================================

-- admissions (public schema)
ALTER TABLE admissions
    ADD COLUMN IF NOT EXISTS external_visit_number          VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS external_sending_application   VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS external_sending_facility      VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS external_message_control_id    VARCHAR(255) NULL;

-- Composite partial unique index. Scoped per (sender, hospital) so the
-- same visit number landing at a different hospital or from a different
-- sender does not collide. Partial (visit number NOT NULL) keeps every
-- existing manually-created row out of the constraint.
CREATE UNIQUE INDEX IF NOT EXISTS uk_admission_external_visit
    ON admissions (external_sending_application,
                   external_sending_facility,
                   external_visit_number,
                   hospital_id)
 WHERE external_visit_number IS NOT NULL;

-- encounters (clinical schema)
ALTER TABLE clinical.encounters
    ADD COLUMN IF NOT EXISTS external_visit_number          VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS external_sending_application   VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS external_sending_facility      VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS external_message_control_id    VARCHAR(255) NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_encounter_external_visit
    ON clinical.encounters (external_sending_application,
                            external_sending_facility,
                            external_visit_number,
                            hospital_id)
 WHERE external_visit_number IS NOT NULL;
