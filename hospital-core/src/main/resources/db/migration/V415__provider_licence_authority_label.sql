-- V415: provider facilities — the licence authority as typed (QA r81 D4074),
-- and one VERIFIED facility per CNSS number (QA r81 D4312).
--
-- licence_authority is the upper-cased half of the (authority, licence)
-- uniqueness key of V180; screens showed that key, so an authority typed in
-- mixed case read in capitals. licence_authority_label keeps the typed text.
-- Rows older than this migration keep NULL and show the key, as before.
--
-- V180 holds one VERIFIED row per RCCM and per IFU number; the CNSS number
-- names the same legal entity and had no such index. It is created only when
-- no two VERIFIED rows already share a CNSS number, so this migration cannot
-- fail a deploy; the service refuses the duplicate in every case.
--
-- Additive and idempotent. No rollback declared (forward-only).

ALTER TABLE hospital.provider_verifications
    ADD COLUMN IF NOT EXISTS licence_authority_label VARCHAR(200);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM hospital.provider_verifications
        WHERE status = 'VERIFIED'
        GROUP BY cnss_number
        HAVING COUNT(*) > 1
    ) THEN
        CREATE UNIQUE INDEX IF NOT EXISTS uq_provider_cnss_verified
            ON hospital.provider_verifications (cnss_number)
            WHERE status = 'VERIFIED';
    END IF;
END $$;
