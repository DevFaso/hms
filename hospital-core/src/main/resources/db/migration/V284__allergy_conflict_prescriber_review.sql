-- V284: Policy 4 (user recommendation 2026-11-10, pending clinical approval; replaces the provisional
-- default of #1108). An allergy conflict found after the signature (CurrentAllergyRecheck) is no longer
-- released by the nurse's or the pharmacist's own reason: an authorized prescriber reviews it on the order.
--
-- The review lives on the order: the decision (CONTINUE with a plan, or STOP), the rationale, the reviewer,
-- the time, and the token of the conflict as it stood (the allergy state and the order's drug, dose, route
-- and frequency). A gate releases the order only while the conflict's current token equals the reviewed one,
-- so any later change to the allergy or the order needs a renewed review.
--
-- Plan and rationale are clinical narrative: encrypted by the entity (EncryptedStringConverter), so TEXT.
-- Existing rows were never reviewed: NULL, no backfill. No DO block, so no splitStatements attribute.

ALTER TABLE clinical.prescriptions
    ADD COLUMN IF NOT EXISTS allergy_review_decision VARCHAR(20),
    ADD COLUMN IF NOT EXISTS allergy_review_token    VARCHAR(64),
    ADD COLUMN IF NOT EXISTS allergy_review_plan      TEXT,
    ADD COLUMN IF NOT EXISTS allergy_review_rationale TEXT,
    ADD COLUMN IF NOT EXISTS allergy_reviewed_at      TIMESTAMP,
    ADD COLUMN IF NOT EXISTS allergy_reviewed_by      UUID;

ALTER TABLE clinical.prescriptions
    DROP CONSTRAINT IF EXISTS chk_rx_allergy_review_decision;
ALTER TABLE clinical.prescriptions
    ADD CONSTRAINT chk_rx_allergy_review_decision
        CHECK (allergy_review_decision IS NULL OR allergy_review_decision IN ('CONTINUE', 'STOP'));

ALTER TABLE clinical.prescriptions
    DROP CONSTRAINT IF EXISTS fk_rx_allergy_reviewed_by;
ALTER TABLE clinical.prescriptions
    ADD CONSTRAINT fk_rx_allergy_reviewed_by
        FOREIGN KEY (allergy_reviewed_by)
        REFERENCES security.users (id)
        ON DELETE SET NULL
        NOT VALID;
