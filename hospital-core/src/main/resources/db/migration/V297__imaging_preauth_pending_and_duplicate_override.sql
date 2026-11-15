-- V297 (provisional rules of 2026-11-15, pending the radiology approver):
--
-- Rule 1, urgent imaging is never blocked by a pre-authorisation. An URGENT
-- or STAT study whose pre-authorisation is required starts without its
-- number; authorization_pending_since records when the study was done
-- without it (« Préautorisation en attente »), and is cleared when the number
-- is recorded. Set and cleared only by ImagingPreAuthorisationPolicy.
--
-- Rule 2, overriding the duplicate warning records a reason. « Commander
-- quand même » stores the override, a reason code from a fixed list
-- (ImagingDuplicateOverrideReason), an optional note (required with OTHER,
-- encrypted by EncryptedStringConverter, hence TEXT), who and when.
--
-- New nullable columns only: existing orders are neither pending nor
-- overridden. Idempotent.

ALTER TABLE clinical.imaging_orders
    ADD COLUMN IF NOT EXISTS authorization_pending_since TIMESTAMP;

ALTER TABLE clinical.imaging_orders
    ADD COLUMN IF NOT EXISTS duplicate_override BOOLEAN;

ALTER TABLE clinical.imaging_orders
    ADD COLUMN IF NOT EXISTS duplicate_override_reason VARCHAR(40);

ALTER TABLE clinical.imaging_orders
    ADD COLUMN IF NOT EXISTS duplicate_override_note TEXT;

ALTER TABLE clinical.imaging_orders
    ADD COLUMN IF NOT EXISTS duplicate_override_by_user_id UUID;

ALTER TABLE clinical.imaging_orders
    ADD COLUMN IF NOT EXISTS duplicate_override_by_name VARCHAR(150);

ALTER TABLE clinical.imaging_orders
    ADD COLUMN IF NOT EXISTS duplicate_override_at TIMESTAMP;
