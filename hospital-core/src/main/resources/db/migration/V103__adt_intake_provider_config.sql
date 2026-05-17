-- =================================================================
-- V103 — Per-hospital ADT intake-provider configuration.
-- Roadmap row 24 follow-on (v1.1 / Interop HL7 / ADT → Admission /
-- Encounter sync). The foundation pass on feat/v1.1-adt-admission-
-- encounter-sync (V99) added the visit-number reconciliation columns;
-- the no-match branch in MllpInboundAdtVisitProjectionServiceImpl
-- explicitly deferred auto-create pending per-hospital intake
-- configuration. This migration adds that configuration table.
--
-- One row per hospital, scoping which staff member, department,
-- admission type, encounter type, acuity level, and chief-complaint
-- default to stamp on an auto-created Admission when an ADT^A01
-- arrives without an existing visit-number match. The per-hospital
-- `enabled` column gates auto-create on top of the global
-- app.hl7.adt.visit-sync.auto-create.enabled flag, so a tenant can
-- opt out even when the cluster-wide flag is on.
--
-- Idempotency: every statement is strictly additive
-- (CREATE TABLE IF NOT EXISTS, CREATE INDEX IF NOT EXISTS). Hard FK
-- on hospital_id only; admitting_provider_id and department_id are
-- intentionally NOT FK-constrained so an operator can re-seed staff
-- or department rebuilds without dropping the config first.
-- Application-layer reads dereference the UUIDs and reject auto-
-- create gracefully when a referent is missing.
--
-- Forward-only; no automated rollback declared.
-- =================================================================

CREATE TABLE IF NOT EXISTS platform.adt_intake_provider_configs (
    id                       UUID         NOT NULL PRIMARY KEY,
    hospital_id              UUID         NOT NULL,
    admitting_provider_id    UUID         NOT NULL,
    department_id            UUID         NULL,
    default_admission_type   VARCHAR(64)  NOT NULL,
    default_acuity_level     VARCHAR(32)  NOT NULL,
    default_encounter_type   VARCHAR(64)  NOT NULL,
    default_chief_complaint  VARCHAR(500) NOT NULL DEFAULT 'Auto-created from ADT^A01',
    enabled                  BOOLEAN      NOT NULL DEFAULT false,
    created_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_adt_intake_config_hospital
        FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals(id)
);

-- One config per hospital. Partial index isn't needed — the FK
-- guarantees a valid hospital, and the business rule is exactly one
-- intake config per hospital. A second row would mean ambiguous
-- defaults and must be rejected at write time.
CREATE UNIQUE INDEX IF NOT EXISTS uk_adt_intake_config_hospital
    ON platform.adt_intake_provider_configs (hospital_id);

-- The auto-create lookup is keyed on (hospital_id, enabled). Index
-- covers the hot read pattern from MllpInboundAdtVisitProjectionService.
CREATE INDEX IF NOT EXISTS idx_adt_intake_config_hospital_enabled
    ON platform.adt_intake_provider_configs (hospital_id, enabled);

-- Documentation comments — visible via psql \d+ for operators
-- triaging an auto-create reject.
COMMENT ON TABLE platform.adt_intake_provider_configs IS
    'Per-hospital ADT auto-create defaults. Consumed by MllpInboundAdtVisitProjectionService when an ADT^A01 with an unmatched visit-number triplet arrives and app.hl7.adt.visit-sync.auto-create.enabled=true. See docs/runbooks/hl7-adt-conflict-resolution.md.';

COMMENT ON COLUMN platform.adt_intake_provider_configs.admitting_provider_id IS
    'UUID of hospital.staff to stamp as Admission.admittingProvider. Not FK-constrained — application-layer lookup rejects auto-create if the row is missing.';

COMMENT ON COLUMN platform.adt_intake_provider_configs.default_admission_type IS
    'String form of com.example.hms.enums.AdmissionType (EMERGENCY / ELECTIVE / URGENT / ...). Application-layer enum-validated on write.';

COMMENT ON COLUMN platform.adt_intake_provider_configs.enabled IS
    'Per-hospital opt-in. Auto-create only fires when this is true AND the global app.hl7.adt.visit-sync.auto-create.enabled flag is true.';
