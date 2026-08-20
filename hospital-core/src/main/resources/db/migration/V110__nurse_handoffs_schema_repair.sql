-- ─────────────────────────────────────────────────────────────────────────────
-- Repair migration for clinical.nurse_handoffs schema drift (dev).
--
-- The original V108 deployment died mid-changeset and left a
-- clinical.nurse_handoffs table that does NOT match the NurseHandoff entity
-- (Hibernate ddl-auto=validate failed on "missing column [assessment]").
-- After the V108 idempotency fix, Liquibase recorded the changeset with the
-- CREATE TABLE skipped, so the drifted table survived.
--
-- This changeset backfills every entity-mapped column additively. On healthy
-- databases (uat/main get the full table from V108 first time) every
-- statement no-ops. Columns are added nullable — Hibernate validation checks
-- existence and type, not nullability, and the entity's bean validation
-- enforces required fields at write time.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE clinical.nurse_handoffs ADD COLUMN IF NOT EXISTS patient_id        UUID;
ALTER TABLE clinical.nurse_handoffs ADD COLUMN IF NOT EXISTS hospital_id       UUID;
ALTER TABLE clinical.nurse_handoffs ADD COLUMN IF NOT EXISTS direction         VARCHAR(200);
ALTER TABLE clinical.nurse_handoffs ADD COLUMN IF NOT EXISTS situation         TEXT;
ALTER TABLE clinical.nurse_handoffs ADD COLUMN IF NOT EXISTS background        TEXT;
ALTER TABLE clinical.nurse_handoffs ADD COLUMN IF NOT EXISTS assessment        TEXT;
ALTER TABLE clinical.nurse_handoffs ADD COLUMN IF NOT EXISTS recommendation    TEXT;
ALTER TABLE clinical.nurse_handoffs ADD COLUMN IF NOT EXISTS status            VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE clinical.nurse_handoffs ADD COLUMN IF NOT EXISTS created_by_name   VARCHAR(200);
ALTER TABLE clinical.nurse_handoffs ADD COLUMN IF NOT EXISTS completed_by_name VARCHAR(200);
ALTER TABLE clinical.nurse_handoffs ADD COLUMN IF NOT EXISTS completed_at      TIMESTAMP;
ALTER TABLE clinical.nurse_handoffs ADD COLUMN IF NOT EXISTS created_at        TIMESTAMP NOT NULL DEFAULT now();
ALTER TABLE clinical.nurse_handoffs ADD COLUMN IF NOT EXISTS updated_at        TIMESTAMP NOT NULL DEFAULT now();
