-- QA D1429 (round 34): a desk booking outside any published slot keeps why it was
-- overbooked. NULL for a booking on a published free slot, and for every row before this.
ALTER TABLE clinical.appointments
    ADD COLUMN IF NOT EXISTS overbook_reason VARCHAR(500);

-- QA D1430 (round 34): why a hospital registration was deactivated. Cleared when it is
-- reactivated; NULL for an active registration.
ALTER TABLE clinical.patient_hospital_registrations
    ADD COLUMN IF NOT EXISTS deactivation_reason VARCHAR(500);
