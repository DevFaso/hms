-- =============================================================
-- V4: Make hospital.staff.license_number nullable
--
-- Non-clinical staff (RECEPTIONIST, ADMIN, TECHNICIAN, etc.)
-- do not hold a medical license. The original schema defined
-- this column as NOT NULL which blocks creating non-clinical
-- staff records. Clinical-role validation is enforced at the
-- application layer (StaffServiceImpl.validateLicenseNumber).
-- =============================================================

ALTER TABLE hospital.staff
    ALTER COLUMN license_number DROP NOT NULL;
