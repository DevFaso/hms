ALTER TABLE security.notification_preferences
    ADD COLUMN IF NOT EXISTS recorded_by_user_id uuid,
    ADD COLUMN IF NOT EXISTS recorded_at timestamp;

ALTER TABLE clinical.patient_notification_deliveries
    ADD COLUMN IF NOT EXISTS consent_recorded_by uuid,
    ADD COLUMN IF NOT EXISTS consent_recorded_at timestamp;
