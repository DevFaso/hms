-- =====================================================================
-- V97: Hospital tenant isolation mode (roadmap row 33, v2.0 / Multi-tenancy)
--
-- Adds two columns to hospital.hospitals so a super admin can flag a
-- hospital as schema-isolated (military, foreign-private, regulated
-- jurisdictions) instead of the default row-level multi-tenancy.
--
--   isolation_mode      ROW_LEVEL (default) | SCHEMA
--   tenant_schema_name  PG identifier of the dedicated schema, or NULL
--
-- This migration is purely metadata — no data is moved, no tenant
-- schemas are created. The connection-provider plumbing
-- (SchemaTenantConnectionProvider, SchemaTenantIdentifierResolver) is
-- gated by app.tenancy.schema-isolation.enabled, off by default. See
-- docs/runbooks/schema-per-tenant-migration.md for the full operator
-- procedure that creates the schema, copies the data, and flips
-- isolation_mode atomically.
--
-- Strictly additive — both columns have a default or are nullable.
-- Rollback drops the columns; the JPA mapping shipped with the
-- rollback must omit them too (Hospital.isolationMode +
-- Hospital.tenantSchemaName).
--
-- Idempotency: ADD COLUMN IF NOT EXISTS + the CHECK is wrapped in a
-- DO block so re-runs don't double-add the constraint.
-- =====================================================================

ALTER TABLE hospital.hospitals
    ADD COLUMN IF NOT EXISTS isolation_mode      VARCHAR(16) NOT NULL DEFAULT 'ROW_LEVEL',
    ADD COLUMN IF NOT EXISTS tenant_schema_name  VARCHAR(63) NULL;

-- Allowed values map exactly to com.example.hms.enums.TenantIsolationMode.
-- Adding a new mode requires a new migration that drops + recreates the
-- check; treat the enum as the source of truth and keep this list in sync.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint c
          JOIN pg_class t ON t.oid = c.conrelid
          JOIN pg_namespace n ON n.oid = t.relnamespace
         WHERE n.nspname = 'hospital'
           AND t.relname = 'hospitals'
           AND c.conname = 'ck_hospital_isolation_mode'
    ) THEN
        ALTER TABLE hospital.hospitals
            ADD CONSTRAINT ck_hospital_isolation_mode
            CHECK (isolation_mode IN ('ROW_LEVEL', 'SCHEMA'));
    END IF;
END $$;

-- A hospital flagged SCHEMA must name its schema; a hospital flagged
-- ROW_LEVEL must NOT (the column is meaningless under row-level mode
-- and a stale value would mislead the resolver if the flag flipped).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint c
          JOIN pg_class t ON t.oid = c.conrelid
          JOIN pg_namespace n ON n.oid = t.relnamespace
         WHERE n.nspname = 'hospital'
           AND t.relname = 'hospitals'
           AND c.conname = 'ck_hospital_tenant_schema_consistency'
    ) THEN
        ALTER TABLE hospital.hospitals
            ADD CONSTRAINT ck_hospital_tenant_schema_consistency
            CHECK (
                (isolation_mode = 'ROW_LEVEL' AND tenant_schema_name IS NULL)
             OR (isolation_mode = 'SCHEMA'    AND tenant_schema_name IS NOT NULL)
            );
    END IF;
END $$;

-- Two hospitals can never share the same schema. Partial unique index
-- because most rows have NULL tenant_schema_name (ROW_LEVEL default).
CREATE UNIQUE INDEX IF NOT EXISTS uk_hospital_tenant_schema_name
    ON hospital.hospitals (tenant_schema_name)
 WHERE tenant_schema_name IS NOT NULL;
