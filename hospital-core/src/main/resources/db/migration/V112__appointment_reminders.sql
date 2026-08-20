-- ─────────────────────────────────────────────────────────────────────────────
-- P1 #7: SMS appointment reminders. Idempotency stamp on clinical.appointments
-- so the reminder sweep sends exactly one reminder per appointment — the enum
-- (NotificationType.APPOINTMENT_REMINDER) existed but nothing ever sent.
-- IF NOT EXISTS throughout (house rule since the V108/V110 incident).
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE clinical.appointments ADD COLUMN IF NOT EXISTS reminder_sent_at TIMESTAMP;

-- Partial index sized for the sweep: upcoming, not yet reminded.
CREATE INDEX IF NOT EXISTS idx_appointments_reminder_pending
    ON clinical.appointments (appointment_date)
    WHERE reminder_sent_at IS NULL;
