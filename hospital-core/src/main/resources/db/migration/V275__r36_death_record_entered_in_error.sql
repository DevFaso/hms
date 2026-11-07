-- V275: QA D1488 (round 36). A recorded death now refuses new care, so a death recorded on the wrong
-- patient would lock a living patient out, and V135 had no way back. The row is kept and marked entered
-- in error, with who, when and why. It leaves the register and the WHO indicators.
--
-- The unique index becomes partial: a person dies once, but a row entered in error is not a death, so the
-- patient can still have a real record later. Idempotent by rule.

ALTER TABLE clinical.death_records
    ADD COLUMN IF NOT EXISTS entered_in_error_at      TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS entered_in_error_by_id   UUID,
    ADD COLUMN IF NOT EXISTS entered_in_error_by_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS entered_in_error_reason  VARCHAR(500);

DROP INDEX IF EXISTS clinical.uq_death_record_patient;

CREATE UNIQUE INDEX IF NOT EXISTS uq_death_record_patient_live
    ON clinical.death_records (patient_id)
    WHERE entered_in_error_at IS NULL;

-- QA CX-012: an incompatible result on a bag issued to a request stands, and the bag's status cannot say so:
-- an issued bag found incompatible goes back to RETURNED, as a never-issued one does. The row keeps the fact.
-- For rows already written the history is not on file, so a choice is made and it is this: a result stands rather
-- than risk one being erased. An incompatible row on a request that reached ISSUED or COMPLETED is taken as
-- issued, even if that request issued another bag and not this one. A row on a request since CANCELLED cannot be
-- told apart, and is left: that request takes no compatible result itself.

ALTER TABLE clinical.transfusion_crossmatches
    ADD COLUMN IF NOT EXISTS unit_issued BOOLEAN NOT NULL DEFAULT false;

UPDATE clinical.transfusion_crossmatches c
   SET unit_issued = true
  FROM clinical.blood_units u, clinical.transfusion_requests r
 WHERE u.id = c.blood_unit_id
   AND r.id = c.request_id
   AND ((u.request_id = c.request_id AND u.status = 'ISSUED')
        OR (c.compatible = false AND r.status IN ('ISSUED', 'COMPLETED'))
        OR EXISTS (SELECT 1 FROM clinical.transfusion_administrations a
                    WHERE a.blood_unit_id = c.blood_unit_id AND a.request_id = c.request_id)
        OR EXISTS (SELECT 1 FROM clinical.emergency_blood_releases e
                    WHERE e.blood_unit_id = c.blood_unit_id AND e.request_id = c.request_id));
