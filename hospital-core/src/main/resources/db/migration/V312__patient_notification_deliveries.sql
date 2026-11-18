-- =====================================================================
-- V312: the patient notification delivery ledger
--       (clinical.patient_notification_deliveries), go-live scorecard K1/K3.
--
-- Appointment reminders went out as a best-effort SMS with nothing kept: a
-- failed send was one WARN line, never retried, and no one at the hospital
-- could see it. Released results reached the patient only as an in-app bell,
-- so a patient without the app was never told. Appointment confirmations by
-- SMS did not exist.
--
-- One row per (event, subject, channel): an appointment confirmation or
-- reminder, a lab order, an imaging order or an ultrasound report, sent by
-- SMS or by email. The row is the unit of retry and of duplicate prevention:
--
--   * idempotency_key (event:subject:channel) is UNIQUE, so a scheduler
--     re-run, a second instance, or the same release reached twice can never
--     queue a second message for the same thing on the same channel.
--   * channel: SMS or EMAIL today. No CHECK: the column maps the shared
--     NotificationChannel enum (IN_APP, PUSH too), and EntitySchemaValidationIT
--     refuses a CHECK narrower than the enum the entity can write.
--   * status: PENDING -> SENT | RETRYING -> ... -> SENT | FAILED, or SKIPPED
--     when sending is no longer right (opted out, number removed, result
--     withdrawn, recipient not on this environment's allowlist).
--   * attempts / next_attempt_at / last_attempt_at: the mail outbox's (V173)
--     claim-then-send vocabulary. next_attempt_at is the doubling backoff
--     after a failure and the claim lease while an instance sends.
--   * error_code: a fixed code (PROVIDER_UNREACHABLE, RECIPIENT_NOT_ALLOWED,
--     ...), never a transport message, which can quote the number.
--   * manual_retries / last_retried_by / last_retried_at: a hospital
--     administrator put a FAILED row back in the queue.
--
-- No message body, no phone number, no email address is stored: the message
-- is rendered from the subject at send time, in the patient's language, and
-- the recipient read from the patient record then. Nothing here is PHI beyond
-- the patient and subject ids.
--
-- Strictly additive: CREATE TABLE / CREATE INDEX IF NOT EXISTS only. To undo,
-- DROP TABLE clinical.patient_notification_deliveries (queued sends are lost).
-- =====================================================================

CREATE TABLE IF NOT EXISTS clinical.patient_notification_deliveries (
    id               UUID          NOT NULL,
    hospital_id      UUID          NOT NULL,
    patient_id       UUID          NOT NULL,
    event_type       VARCHAR(40)   NOT NULL,
    subject_id       UUID          NOT NULL,
    channel          VARCHAR(10)   NOT NULL,
    idempotency_key  VARCHAR(120)  NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    attempts         INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMP     NOT NULL,
    last_attempt_at  TIMESTAMP,
    sent_at          TIMESTAMP,
    error_code       VARCHAR(60),
    manual_retries   INTEGER       NOT NULL DEFAULT 0,
    last_retried_by  UUID,
    last_retried_at  TIMESTAMP,
    created_at       TIMESTAMP     NOT NULL,
    updated_at       TIMESTAMP     NOT NULL,

    CONSTRAINT pk_patient_notification_deliveries PRIMARY KEY (id),
    CONSTRAINT uq_pnd_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_pnd_hospital FOREIGN KEY (hospital_id)
        REFERENCES hospital.hospitals (id) ON DELETE CASCADE,
    CONSTRAINT fk_pnd_patient FOREIGN KEY (patient_id)
        REFERENCES clinical.patients (id) ON DELETE CASCADE,
    CONSTRAINT chk_pnd_status CHECK (status IN ('PENDING', 'RETRYING', 'SENT', 'FAILED', 'SKIPPED'))
);

-- The sweep: due PENDING / RETRYING rows, most overdue first.
CREATE INDEX IF NOT EXISTS idx_pnd_dispatch
    ON clinical.patient_notification_deliveries (status, next_attempt_at);

-- The staff view: one hospital's rows by status, newest first.
CREATE INDEX IF NOT EXISTS idx_pnd_hospital_status
    ON clinical.patient_notification_deliveries (hospital_id, status, created_at);

CREATE INDEX IF NOT EXISTS idx_pnd_patient
    ON clinical.patient_notification_deliveries (patient_id);
