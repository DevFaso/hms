ALTER TABLE "security"."user_role_hospital_assignment"
    ADD COLUMN IF NOT EXISTS confirmation_code VARCHAR(16);

ALTER TABLE "security"."user_role_hospital_assignment"
    ADD COLUMN IF NOT EXISTS confirmation_sent_at TIMESTAMP;

ALTER TABLE "security"."user_role_hospital_assignment"
    ADD COLUMN IF NOT EXISTS confirmation_verified_at TIMESTAMP;
