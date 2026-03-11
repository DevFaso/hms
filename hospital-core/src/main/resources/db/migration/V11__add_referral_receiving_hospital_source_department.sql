-- V11: Add receiving_hospital_id and source_department_id to general_referrals
-- Supports the redesigned referral workflow with explicit source/destination
-- hospital and department tracking.

ALTER TABLE general_referrals
    ADD COLUMN IF NOT EXISTS receiving_hospital_id UUID,
    ADD COLUMN IF NOT EXISTS source_department_id  UUID;

ALTER TABLE general_referrals
    ADD CONSTRAINT fk_referral_receiving_hospital
        FOREIGN KEY (receiving_hospital_id) REFERENCES hospital.hospitals(id),
    ADD CONSTRAINT fk_referral_source_department
        FOREIGN KEY (source_department_id) REFERENCES hospital.departments(id);

CREATE INDEX IF NOT EXISTS idx_referral_receiving_hospital
    ON general_referrals (receiving_hospital_id);
