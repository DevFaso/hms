-- QA D1613 (round 44): one live pharmacy claim per dispense.
--
-- PharmacyClaimServiceImpl.createClaim refuses a second claim while the
-- dispense carries one that is not REJECTED (it holds the dispense row lock).
-- This partial unique index is the database's second line for the same rule:
-- a REJECTED claim is the one a resubmission replaces, so it is left out.
--
-- A database that already holds two live claims for one dispense (created
-- before the fix) is not altered: the index is skipped with a warning, the
-- service check still applies to every new claim, and the duplicates are left
-- for the hospital to reject by hand. Claims are financial records, so this
-- migration never changes their status itself.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM billing.pharmacy_claims
         WHERE claim_status <> 'REJECTED'
         GROUP BY dispense_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE WARNING 'V288: billing.pharmacy_claims holds several live claims for one dispense; '
            'uq_pc_dispense_live not created. Reject the duplicates, then create the index.';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS uq_pc_dispense_live
            ON billing.pharmacy_claims (dispense_id)
            WHERE claim_status <> 'REJECTED';
    END IF;
END
$$;
