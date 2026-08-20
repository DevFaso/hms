-- V107: Phone-first registration + IKODDI SMS OTP.
-- (1) security.phone_otp_challenges — pending SMS OTP dispatches: one row per
--     send holding the opaque IKODDI verification key; consumed/expired rows
--     are inert. Codes themselves never touch the database (IKODDI verifies).
-- (2) clinical.patients.phone_verified_at — set when a registration OTP was
--     confirmed for the patient's primary phone.
-- (3) email becomes OPTIONAL on both identity tables: most patients have a
--     phone number but no email address. The unique email indexes stay valid —
--     Postgres unique indexes ignore NULLs.

CREATE TABLE IF NOT EXISTS security.phone_otp_challenges (
    id UUID PRIMARY KEY,
    phone_number VARCHAR(30) NOT NULL,
    purpose VARCHAR(50) NOT NULL,
    verification_key TEXT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    consumed BOOLEAN NOT NULL DEFAULT FALSE,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    used_for_registration BOOLEAN NOT NULL DEFAULT FALSE,
    attempts INTEGER NOT NULL DEFAULT 0,
    requested_by_user_id UUID NOT NULL,
    hospital_id UUID,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_potp_phone_purpose
    ON security.phone_otp_challenges (phone_number, purpose);
CREATE INDEX IF NOT EXISTS idx_potp_requested_by
    ON security.phone_otp_challenges (requested_by_user_id);

ALTER TABLE clinical.patients ADD COLUMN IF NOT EXISTS phone_verified_at TIMESTAMP;

ALTER TABLE clinical.patients ALTER COLUMN email DROP NOT NULL;
ALTER TABLE security.users ALTER COLUMN email DROP NOT NULL;
