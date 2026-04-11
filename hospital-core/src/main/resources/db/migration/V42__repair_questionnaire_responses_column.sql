-- =========================================================================
-- V42: Repair — ensure clinical.questionnaire_responses has the "responses"
-- column.
--
-- Root cause: the table was pre-created (by V1 initial schema or Hibernate
-- auto-DDL) before V39 ran.  Because V39 uses CREATE TABLE IF NOT EXISTS,
-- the entire CREATE was silently skipped, leaving the "responses" column
-- missing.  Hibernate schema-validation then fails:
--   "missing column [responses] in table [clinical.questionnaire_responses]"
--
-- Fix: idempotent ADD COLUMN IF NOT EXISTS.
-- =========================================================================

ALTER TABLE clinical.questionnaire_responses
    ADD COLUMN IF NOT EXISTS responses JSONB NOT NULL DEFAULT '{}'::jsonb;
