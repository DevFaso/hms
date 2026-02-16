-- ============================================================
-- R__prod_role_grants.sql
-- Repeatable migration: grants schema-level privileges to
-- hms_app, hms_readonly, and hms_migrator roles.
--
-- This is a Liquibase "repeatable" changeset (R__ prefix).
-- It re-runs whenever the file checksum changes.
--
-- Roles:
--   postgres      → superuser, owns everything (emergency only)
--   hms_migrator  → CREATE/ALTER/DROP (CI/CD migrations)
--   hms_app       → SELECT/INSERT/UPDATE/DELETE (Spring Boot)
--   hms_readonly  → SELECT only (developer investigation)
-- ============================================================

-- ===================== SCHEMA ACCESS =====================

-- hms_migrator: full DDL access to all schemas
DO $$
DECLARE
  s TEXT;
  schemas TEXT[] := ARRAY['billing','clinical','empi','governance','hospital','lab','platform','reference','security','support','public'];
BEGIN
  FOREACH s IN ARRAY schemas LOOP
    EXECUTE format('GRANT ALL ON SCHEMA %I TO hms_migrator', s);
  END LOOP;
END $$;

-- hms_app: USAGE only (no CREATE TABLE)
DO $$
DECLARE
  s TEXT;
  schemas TEXT[] := ARRAY['billing','clinical','empi','governance','hospital','lab','platform','reference','security','support','public'];
BEGIN
  FOREACH s IN ARRAY schemas LOOP
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO hms_app', s);
  END LOOP;
END $$;

-- hms_readonly: USAGE only (browse schema objects)
DO $$
DECLARE
  s TEXT;
  schemas TEXT[] := ARRAY['billing','clinical','empi','governance','hospital','lab','platform','reference','security','support','public'];
BEGIN
  FOREACH s IN ARRAY schemas LOOP
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO hms_readonly', s);
  END LOOP;
END $$;

-- ===================== TABLE GRANTS (EXISTING) =====================

-- hms_app: CRUD on all existing tables
DO $$
DECLARE
  s TEXT;
  schemas TEXT[] := ARRAY['billing','clinical','empi','governance','hospital','lab','platform','reference','security','support','public'];
BEGIN
  FOREACH s IN ARRAY schemas LOOP
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO hms_app', s);
    EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO hms_app', s);
  END LOOP;
END $$;

-- hms_readonly: SELECT only on all existing tables
DO $$
DECLARE
  s TEXT;
  schemas TEXT[] := ARRAY['billing','clinical','empi','governance','hospital','lab','platform','reference','security','support','public'];
BEGIN
  FOREACH s IN ARRAY schemas LOOP
    EXECUTE format('GRANT SELECT ON ALL TABLES IN SCHEMA %I TO hms_readonly', s);
    EXECUTE format('GRANT SELECT ON ALL SEQUENCES IN SCHEMA %I TO hms_readonly', s);
  END LOOP;
END $$;

-- hms_migrator: full access on all existing tables (needed for data migrations)
DO $$
DECLARE
  s TEXT;
  schemas TEXT[] := ARRAY['billing','clinical','empi','governance','hospital','lab','platform','reference','security','support','public'];
BEGIN
  FOREACH s IN ARRAY schemas LOOP
    EXECUTE format('GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA %I TO hms_migrator', s);
    EXECUTE format('GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA %I TO hms_migrator', s);
  END LOOP;
END $$;

-- ===================== DEFAULT PRIVILEGES (FUTURE TABLES) =====================
-- When postgres (superuser) creates tables via Liquibase, auto-grant to all roles.

DO $$
DECLARE
  s TEXT;
  schemas TEXT[] := ARRAY['billing','clinical','empi','governance','hospital','lab','platform','reference','security','support','public'];
BEGIN
  FOREACH s IN ARRAY schemas LOOP
    -- Future tables created by postgres → hms_app gets CRUD
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO hms_app', s);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA %I GRANT USAGE, SELECT ON SEQUENCES TO hms_app', s);

    -- Future tables created by postgres → hms_readonly gets SELECT
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA %I GRANT SELECT ON TABLES TO hms_readonly', s);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA %I GRANT SELECT ON SEQUENCES TO hms_readonly', s);

    -- Future tables created by postgres → hms_migrator gets ALL
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA %I GRANT ALL PRIVILEGES ON TABLES TO hms_migrator', s);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA %I GRANT ALL PRIVILEGES ON SEQUENCES TO hms_migrator', s);

    -- Future tables created by hms_migrator → hms_app gets CRUD
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE hms_migrator IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO hms_app', s);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE hms_migrator IN SCHEMA %I GRANT USAGE, SELECT ON SEQUENCES TO hms_app', s);

    -- Future tables created by hms_migrator → hms_readonly gets SELECT
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE hms_migrator IN SCHEMA %I GRANT SELECT ON TABLES TO hms_readonly', s);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE hms_migrator IN SCHEMA %I GRANT SELECT ON SEQUENCES TO hms_readonly', s);
  END LOOP;
END $$;

-- ===================== EXPLICIT DENIALS =====================
-- hms_readonly cannot TRUNCATE, INSERT, UPDATE, DELETE (belt + suspenders)
-- (PostgreSQL doesn't have explicit DENY, so we simply never GRANT those)

-- hms_app cannot DROP or CREATE tables
-- (No GRANT ALL ON SCHEMA, only USAGE — so CREATE TABLE will fail)
