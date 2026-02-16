-- =============================================================
-- V1.1: Add unique constraints missing from V1 initial schema
--
-- The V1 DDL was generated from Hibernate's SchemaExport which
-- does not emit @UniqueConstraint / unique=true annotations.
-- These indexes are required for V2's ON CONFLICT (code) clause
-- and for JPA entity correctness.
--
-- Uses IF NOT EXISTS for idempotency.
-- =============================================================

-- security.roles
CREATE UNIQUE INDEX IF NOT EXISTS uq_role_code     ON security.roles (code);
CREATE UNIQUE INDEX IF NOT EXISTS uq_role_name     ON security.roles (name);

-- hospital.organizations (needed by V3)
CREATE UNIQUE INDEX IF NOT EXISTS uq_org_code      ON hospital.organizations (code);

-- security.users
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_username ON security.users (username);
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_email    ON security.users (email);
