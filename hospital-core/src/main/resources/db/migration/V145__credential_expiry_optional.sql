-- V145: a credential does not have to expire.
--
-- WHY: V140 modelled credentialing on a licence that is renewed on a cycle,
-- and made expiry_date NOT NULL on the strength of it -- "a renewal with no
-- end date is not a renewal, it is a deletion of the expiry rule". That is
-- sound where practice is licensed on a renewable term. It is not how this
-- deployment works.
--
-- Confirmed by the product owner 2026-08-31: clinicians here are credentialed
-- on their diploma. A diploma does not expire, so there is no date to enter
-- and no renewal cycle to track. The NOT NULL meant the credentialing screen
-- could not record the one thing it is actually for -- an administrator had
-- to invent an expiry that does not exist in order to file a qualification.
--
-- WHAT STAYS. Only the expiry becomes optional; nothing is removed. The
-- history table is the durable half of V140 and it earns its place either
-- way: the question it exists to answer is "was this clinician qualified on
-- the day they prescribed that", and that question has the same weight for a
-- diploma as for a licence. It simply has no end date.
--
-- The expiry machinery is kept and left enabled rather than deleted, because
-- a deployment that does track expiring registrations (a foreign-trained
-- clinician, a specialist board) should get its alerts the day it starts
-- entering dates. The sweep already filters on
-- "license_expiry_date IS NOT NULL", so a NULL expiry is skipped rather than
-- treated as expired -- which is why turning the feature off would be the
-- worse default: it would silently do nothing the first time somebody
-- entered a real date.
--
-- Rollback:
--   ALTER TABLE hospital.staff_credential_renewals
--       ALTER COLUMN expiry_date SET NOT NULL;   -- fails if any NULL rows exist
-- =============================================================================

ALTER TABLE hospital.staff_credential_renewals
    ALTER COLUMN expiry_date DROP NOT NULL;

COMMENT ON COLUMN hospital.staff_credential_renewals.expiry_date IS
    'The expiry being recorded, or NULL for a qualification that does not '
    'expire -- a diploma, which is how clinicians are credentialed in this '
    'deployment. A NULL here is a positive statement ("does not expire"), not '
    'missing data: the expiry sweep skips it deliberately.';
