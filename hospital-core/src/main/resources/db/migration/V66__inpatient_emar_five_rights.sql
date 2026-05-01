-- V66: P1 #8 — Inpatient eMAR barcode-scan five-rights verification
--
-- Extends `clinical.medication_administration_records` (created in V15) with
-- the values captured during the bedside barcode scan and the result of the
-- server-side five-rights check. The MAR row remains the single source of
-- truth for an administration event; verification fields are additive so
-- existing rows stay valid (status NOT_VERIFIED until a future scan, or
-- nullable for legacy GIVEN events).
--
-- Five rights: right patient, right drug, right dose, right route, right time.
-- A nurse may proceed past a failed right only by recording an override reason;
-- which rights were overridden are stored in `five_rights_overrides` JSONB so
-- audit can reconstruct the decision without joining a side table.
--
-- Additive only — no destructive ops, no defaults that would mutate history.

ALTER TABLE clinical.medication_administration_records
    ADD COLUMN IF NOT EXISTS patient_scan_value     VARCHAR(255),
    ADD COLUMN IF NOT EXISTS medication_scan_value  VARCHAR(255),
    ADD COLUMN IF NOT EXISTS dose_scan_value        VARCHAR(100),
    ADD COLUMN IF NOT EXISTS route_scan_value       VARCHAR(80),
    ADD COLUMN IF NOT EXISTS scan_verified_at       TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS five_rights_status     VARCHAR(20),
    ADD COLUMN IF NOT EXISTS five_rights_overrides  JSONB,
    ADD COLUMN IF NOT EXISTS override_reason        VARCHAR(1024);

-- Status filter on the bedside dashboards is the dominant access pattern.
CREATE INDEX IF NOT EXISTS idx_mar_five_rights_status
    ON clinical.medication_administration_records (five_rights_status);

-- Audit + safety queries: "show me the doses that proceeded with overrides".
CREATE INDEX IF NOT EXISTS idx_mar_scan_verified_at
    ON clinical.medication_administration_records (scan_verified_at);
