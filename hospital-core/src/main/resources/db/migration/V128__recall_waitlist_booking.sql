-- V128: recall lists + waitlist auto-offer + slot booking (P3 item 22).
--
-- WHY:
--  * Slot booking: the 2026-08-22 decision — THE APPOINTMENT OWNS THE TIME.
--    Booking from a slot creates a normal Appointment and stamps the slot
--    BOOKED + appointment_id (both dead since V121: BOOKED was only ever
--    compared, appointment_id never written). `version` adds the @Version
--    optimistic lock the hold/book check-then-act race needs — the house
--    fix already on ~10 entities (GeneralReferral documents the rationale);
--    uq_slot_staff_start only prevents duplicate ROWS, not concurrent
--    status transitions on one row.
--  * Waitlist offer: offerWaitlistSlot only flipped a status string —
--    no slot, no appointment, no notification, no expiry; the
--    offered_appointment_id FK existed but was never written. These
--    columns carry a real offer: which slot, when offered, when it lapses.
--  * Recalls: no recall concept exists anywhere. Checkout has captured a
--    structured follow-up request since MVP 6 and dropped it on the floor
--    (followUpAppointmentId hardcoded null at both call sites) — this
--    table is where that intent finally lands, plus manual recalls.
--
-- Waitlist columns use TIMESTAMPTZ to match their V19 table; the new
-- table follows the V123/V126 hand-written style. Contains a DO $$ block
-- (guarded FK adds on existing tables), so splitStatements=false.

ALTER TABLE clinical.appointment_slots
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE scheduling.appointment_waitlist
    ADD COLUMN IF NOT EXISTS offered_slot_id UUID;
ALTER TABLE scheduling.appointment_waitlist
    ADD COLUMN IF NOT EXISTS offered_at TIMESTAMPTZ;
ALTER TABLE scheduling.appointment_waitlist
    ADD COLUMN IF NOT EXISTS offer_expires_at TIMESTAMPTZ;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_waitlist_offered_slot'
    ) THEN
        ALTER TABLE scheduling.appointment_waitlist
            ADD CONSTRAINT fk_waitlist_offered_slot
            FOREIGN KEY (offered_slot_id)
            REFERENCES clinical.appointment_slots (id)
            ON DELETE SET NULL;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS scheduling.patient_recalls (
    id                    UUID          NOT NULL DEFAULT gen_random_uuid(),
    patient_id            UUID          NOT NULL,
    hospital_id           UUID          NOT NULL,
    department_id         UUID,
    preferred_provider_id UUID,
    encounter_id          UUID,
    recall_type           VARCHAR(30)   NOT NULL DEFAULT 'FOLLOW_UP',
    status                VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    source                VARCHAR(20)   NOT NULL DEFAULT 'MANUAL',
    due_date              DATE          NOT NULL,
    reason                VARCHAR(500)  NOT NULL,
    notes                 VARCHAR(500),
    notified_at           TIMESTAMP,
    linked_appointment_id UUID,
    closed_at             TIMESTAMP,
    closed_by_user_id     UUID,
    created_by            VARCHAR(255),
    created_at            TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_patient_recalls        PRIMARY KEY (id),
    CONSTRAINT fk_recall_patient         FOREIGN KEY (patient_id)            REFERENCES clinical.patients(id),
    CONSTRAINT fk_recall_hospital        FOREIGN KEY (hospital_id)           REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_recall_department      FOREIGN KEY (department_id)         REFERENCES hospital.departments(id),
    CONSTRAINT fk_recall_provider        FOREIGN KEY (preferred_provider_id) REFERENCES hospital.staff(id) ON DELETE NO ACTION,
    CONSTRAINT fk_recall_encounter       FOREIGN KEY (encounter_id)          REFERENCES clinical.encounters(id),
    CONSTRAINT fk_recall_appointment     FOREIGN KEY (linked_appointment_id) REFERENCES clinical.appointments(id) ON DELETE SET NULL,
    CONSTRAINT fk_recall_closer          FOREIGN KEY (closed_by_user_id)     REFERENCES security.users(id) ON DELETE NO ACTION
);

CREATE INDEX IF NOT EXISTS idx_recall_patient  ON scheduling.patient_recalls (patient_id);
CREATE INDEX IF NOT EXISTS idx_recall_hospital ON scheduling.patient_recalls (hospital_id);
-- The sweep asks "which PENDING recalls come due soon and are unnotified".
CREATE INDEX IF NOT EXISTS idx_recall_due
    ON scheduling.patient_recalls (hospital_id, due_date)
    WHERE status = 'PENDING';
