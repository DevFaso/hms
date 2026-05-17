-- =================================================================
-- V104 — ADT auto-create: default_assignment_id column.
-- Roadmap row 24 follow-on (A04 Encounter-only auto-create).
-- Builds on V103 (per-hospital intake-provider config table).
--
-- Encounter requires a UserRoleHospitalAssignment whose hospital
-- matches the encounter hospital (validated in
-- Encounter#validate, @PrePersist/@PreUpdate). The A01 Admission
-- auto-create from V103 didn't need this; the A04 follow-on does,
-- because Encounter is the entity it provisions.
--
-- Nullable on purpose: hospitals that only opt into A01 auto-create
-- leave the column blank. The service-layer A04 gate fails closed
-- (NO_MATCH + WARN) when the column is null on a row whose hospital
-- has auto-create on — the operator must populate it before A04
-- begins firing.
--
-- Idempotent: ADD COLUMN IF NOT EXISTS. No FK on the new UUID for
-- the same reason V103 didn't FK admitting_provider_id —
-- application-layer lookup tolerates re-seeds of
-- security.user_role_hospital_assignment.
--
-- Forward-only; no automated rollback declared.
-- =================================================================

ALTER TABLE platform.adt_intake_provider_configs
    ADD COLUMN IF NOT EXISTS default_assignment_id UUID NULL;

COMMENT ON COLUMN platform.adt_intake_provider_configs.default_assignment_id IS
    'UUID of security.user_role_hospital_assignment to stamp as Encounter.assignment on A04 auto-create. Nullable; required only when the hospital opts into A04 auto-create. Not FK-constrained — application-layer lookup rejects auto-create if the row is missing.';
