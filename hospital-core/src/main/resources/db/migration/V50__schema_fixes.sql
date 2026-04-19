-- V50: Schema fixes from Copilot review
-- 1. Add missing updated_at column to mfa_backup_codes (entity extends BaseEntity)
-- 2. Drop orphaned hospital.pharmacies table (V46 created it in wrong schema;
--    the Pharmacy entity maps to clinical.pharmacies created by V43)

-- Fix mfa_backup_codes: add updated_at required by BaseEntity mapping
ALTER TABLE security.mfa_backup_codes
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now();

-- Drop orphaned hospital.pharmacies table (entity uses clinical.pharmacies from V43)
DROP TABLE IF EXISTS hospital.pharmacies;
