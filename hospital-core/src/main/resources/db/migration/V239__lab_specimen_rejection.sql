-- V239 (round 16, lab wave).
--
-- QA D962: a specimen could not be rejected. LabSpecimenStatus.REJECTED
-- existed and nothing could reach it: a haemolysed, mislabelled or short
-- sample had to be received or ignored. A rejection now records why, who and
-- when on the specimen itself; the order goes back to awaiting collection and
-- the ordering clinician is told the reason.
--
-- The reason is a short laboratory explanation ("Hémolysé", "Tube
-- inadapté"), never patient-identifying text.
--
-- Schema only, no data change, no PHI. Strictly additive, plain statements,
-- no DO block. Idempotent. No automated rollback is declared.

ALTER TABLE lab.lab_specimens
    ADD COLUMN IF NOT EXISTS rejection_reason VARCHAR(500);

ALTER TABLE lab.lab_specimens
    ADD COLUMN IF NOT EXISTS rejected_at TIMESTAMP;

ALTER TABLE lab.lab_specimens
    ADD COLUMN IF NOT EXISTS rejected_by_id UUID;
