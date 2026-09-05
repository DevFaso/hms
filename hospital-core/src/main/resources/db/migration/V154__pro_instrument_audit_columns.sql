-- V154: the audit columns V153 forgot on three PRO tables.
--
-- WHY: ProInstrumentItem, ProInstrumentOption and ProInstrumentText all
-- extend BaseEntity, which maps created_at and updated_at. V153 gave those
-- columns to pro_instruments and pro_responses only. Every profile that
-- runs against PostgreSQL uses ddl-auto=validate, so the first deploy after
-- #554 refused to build the SessionFactory:
--
--   Schema-validation: missing column [created_at]
--                      in table [clinical.pro_instrument_items]
--
-- V153 is recorded on dev, so it cannot be edited (Liquibase checksums the
-- file). This is the V110 shape: ADD COLUMN IF NOT EXISTS, a no-op on any
-- database that somehow already has the columns.
--
-- Rollback:
--   ALTER TABLE clinical.pro_instrument_items   DROP COLUMN created_at, DROP COLUMN updated_at;
--   ALTER TABLE clinical.pro_instrument_options DROP COLUMN created_at, DROP COLUMN updated_at;
--   ALTER TABLE clinical.pro_instrument_texts   DROP COLUMN created_at, DROP COLUMN updated_at;
-- =============================================================================

ALTER TABLE clinical.pro_instrument_items
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT now();

ALTER TABLE clinical.pro_instrument_options
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT now();

ALTER TABLE clinical.pro_instrument_texts
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT now();
