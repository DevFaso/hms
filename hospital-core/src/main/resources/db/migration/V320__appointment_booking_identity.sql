-- X2: durable booking intent identity. No change to existing clinical rows or slot rules.
-- Explicit request identities remain unique after cancellation; legacy payload identities
-- are unique only while the appointment holds a slot, allowing cancelled visits to be rebooked.
-- Forward-only additive migration; V301-V319 reserved for Claude.
ALTER TABLE clinical.appointments ADD COLUMN IF NOT EXISTS booking_request_key VARCHAR(64);
ALTER TABLE clinical.appointments ADD COLUMN IF NOT EXISTS booking_request_fingerprint VARCHAR(64);
ALTER TABLE clinical.appointments ADD COLUMN IF NOT EXISTS booking_request_durable BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX IF NOT EXISTS uq_appointment_booking_request
    ON clinical.appointments (booking_request_key)
    WHERE booking_request_key IS NOT NULL
      AND (booking_request_durable OR status NOT IN ('CANCELLED', 'NO_SHOW'));
