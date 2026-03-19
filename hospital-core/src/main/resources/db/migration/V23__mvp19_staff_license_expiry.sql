-- ============================================================
-- V23: MVP 19 — Staff Compliance: add license_expiry_date
-- ============================================================

ALTER TABLE hospital.staff
    ADD COLUMN license_expiry_date DATE;

CREATE INDEX idx_staff_license_expiry
    ON hospital.staff (hospital_id, license_expiry_date)
    WHERE license_expiry_date IS NOT NULL AND active = TRUE;
