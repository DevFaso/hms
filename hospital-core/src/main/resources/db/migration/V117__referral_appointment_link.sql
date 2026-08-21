-- =============================================================
-- V117: Link a scheduled referral to the appointment it created.
--
-- WHY
--   Scheduling a referral stored scheduled_appointment_at plus a
--   free-text appointment_location and stopped there. No Appointment
--   row was ever created, so a scheduled referral was invisible to
--   every surface that works off appointments: the receiving
--   provider's calendar, reception check-in, the reminder sweep
--   (V112), and utilisation reporting. The referral said "booked"
--   and the schedule disagreed.
--
--   general_referrals lives in the default (public) schema — see
--   V71/V72, which added referral_events and the @Version column
--   there.
--
-- NULLABLE ON PURPOSE
--   Many referrals go to an external facility with no known
--   receiving provider or department, and an Appointment cannot be
--   built without both (staff_id, department_id and assignment_id
--   are all NOT NULL). Those referrals keep the old behaviour —
--   timestamp plus free text — rather than failing to schedule.
--   A referral that cannot produce an appointment is a normal case,
--   not an error.
-- =============================================================

ALTER TABLE general_referrals
    ADD COLUMN IF NOT EXISTS appointment_id UUID;

CREATE INDEX IF NOT EXISTS idx_referral_appointment
    ON general_referrals (appointment_id);

-- The FK is real here: unlike the tables V1 generated, this column is
-- new, so there is no pre-existing data to violate it. ON DELETE SET
-- NULL rather than CASCADE — deleting an appointment must not delete
-- the referral that prompted it; the referral simply reverts to
-- unlinked.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_referral_appointment'
    ) THEN
        ALTER TABLE general_referrals
            ADD CONSTRAINT fk_referral_appointment
            FOREIGN KEY (appointment_id)
            REFERENCES clinical.appointments (id)
            ON DELETE SET NULL;
    END IF;
END $$;
