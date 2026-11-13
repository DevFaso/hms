-- QA D1592 (round 44): a recorded live birth registers each baby as a patient.
--
-- One row per live-born baby of a delivery, created by
-- NewbornRegistrationService when the delivery is recorded. It links the
-- baby's own patient record to the delivery and to the mother, so newborn
-- assessments, vitals and dosing work on the baby's chart.
--
-- A row is never deleted: when a live-birth count later disagrees with the
-- babies already registered, the extra rows are flagged for review
-- (needs_review) and a person decides. The patient record is never removed.
-- A live birth whose babies are not (yet) registered is marked on the
-- delivery: the registration runs after the delivery commits, in its own
-- transaction, so a failure never undoes the birth; the flag stays until it
-- succeeds, for reception.
ALTER TABLE clinical.delivery_records
    ADD COLUMN IF NOT EXISTS newborn_registration_pending BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS clinical.delivery_newborns (
    id                  UUID          NOT NULL DEFAULT gen_random_uuid(),
    delivery_record_id  UUID          NOT NULL,
    newborn_patient_id  UUID          NOT NULL,
    mother_patient_id   UUID          NOT NULL,
    hospital_id         UUID          NOT NULL,
    birth_order         INTEGER       NOT NULL,
    needs_review        BOOLEAN       NOT NULL DEFAULT false,
    review_reason       VARCHAR(255),
    created_at          TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_delivery_newborns            PRIMARY KEY (id),
    CONSTRAINT uk_delivery_newborn_order       UNIQUE (delivery_record_id, birth_order),
    CONSTRAINT uk_delivery_newborn_patient     UNIQUE (newborn_patient_id),
    CONSTRAINT ck_delivery_newborn_order       CHECK (birth_order >= 1),
    CONSTRAINT fk_delivery_newborn_delivery    FOREIGN KEY (delivery_record_id) REFERENCES clinical.delivery_records(id),
    CONSTRAINT fk_delivery_newborn_patient     FOREIGN KEY (newborn_patient_id) REFERENCES clinical.patients(id),
    CONSTRAINT fk_delivery_newborn_mother      FOREIGN KEY (mother_patient_id)  REFERENCES clinical.patients(id),
    CONSTRAINT fk_delivery_newborn_hospital    FOREIGN KEY (hospital_id)        REFERENCES hospital.hospitals(id)
);

CREATE INDEX IF NOT EXISTS idx_delivery_newborn_mother   ON clinical.delivery_newborns (mother_patient_id);
CREATE INDEX IF NOT EXISTS idx_delivery_newborn_hospital ON clinical.delivery_newborns (hospital_id);
