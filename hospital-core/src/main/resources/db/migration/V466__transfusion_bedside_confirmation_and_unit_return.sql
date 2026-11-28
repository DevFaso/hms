-- V466: the second bedside check is confirmed by the second person, and a bag that left the laboratory
-- comes back through a recorded return (QA D4141, D4178, r81).
--
-- 1. clinical.transfusion_administrations: when the second checker confirmed with their own factor, and by
--    which method (TOTP or PASSWORD). The factor itself is checked and dropped, never stored. NULL on an
--    administration recorded before this migration.
--
-- 2. clinical.blood_units: the laboratory's record of an issued bag coming back (or not): the outcome
--    (FIT_FOR_STOCK, UNFIT_DESTROYED, NOT_RECOVERED), who recorded it, when, and the note. An issued bag is
--    no longer put back into stock by cancelling its request.
--
-- Additive and idempotent. No automated rollback (forward-only).

ALTER TABLE clinical.transfusion_administrations ADD COLUMN IF NOT EXISTS verifier_confirmed_at timestamp;
ALTER TABLE clinical.transfusion_administrations ADD COLUMN IF NOT EXISTS verifier_confirmation_method varchar(20);

ALTER TABLE clinical.blood_units ADD COLUMN IF NOT EXISTS return_outcome varchar(30);
ALTER TABLE clinical.blood_units ADD COLUMN IF NOT EXISTS return_recorded_at timestamp;
ALTER TABLE clinical.blood_units ADD COLUMN IF NOT EXISTS return_recorded_by_user_id uuid;
ALTER TABLE clinical.blood_units ADD COLUMN IF NOT EXISTS return_recorded_by_name varchar(200);
ALTER TABLE clinical.blood_units ADD COLUMN IF NOT EXISTS return_note varchar(500);
