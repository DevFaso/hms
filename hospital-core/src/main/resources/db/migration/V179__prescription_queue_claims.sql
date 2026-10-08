-- V179: pharmacy work-queue claim (audit gap G13).
--
-- A pharmacist can claim a prescription on the dispensing work queue
-- ("being prepared by ..."), release it, or take a colleague's claim over.
-- The claim is advisory: it coordinates people and guards no data (the G15
-- row lock, uq_disp_one_pending_per_rx, Prescription.version and the
-- quantity checks already refuse a double fill).
-- Plan: docs/plan/pharmacy-queue-claim-plan.md.
--
-- 1. One row per prescription (uq_rx_queue_claim_prescription, a FULL
--    unique constraint, also declared on the JPA entity). Claiming over an
--    expired row, renewing and taking over UPDATE the row in place; a
--    release DELETEs it.
-- 2. No hospital_id: every read reaches the claim through a prescription
--    that is already filtered by hospital.
-- 3. Expiry is computed (claimed_at older than the configured TTL), so
--    there is no expiry column and no sweep.
-- 4. created_at / updated_at: the entity extends BaseEntity (V153 -> V154).
-- 5. ON DELETE CASCADE from the prescription (it is hard-deleted) and from
--    the user.
--
-- New, empty table: no backfill, no CHECK constraints, no DO blocks.
-- Forward-only; harmless to the previous application version.

CREATE TABLE IF NOT EXISTS clinical.prescription_queue_claims (
    id              UUID PRIMARY KEY,
    prescription_id UUID NOT NULL,
    claimed_by      UUID NOT NULL,
    claimed_at      TIMESTAMP NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL,
    CONSTRAINT uq_rx_queue_claim_prescription UNIQUE (prescription_id),
    CONSTRAINT fk_rx_queue_claim_prescription FOREIGN KEY (prescription_id)
        REFERENCES clinical.prescriptions (id) ON DELETE CASCADE,
    CONSTRAINT fk_rx_queue_claim_user FOREIGN KEY (claimed_by)
        REFERENCES "security".users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_rx_queue_claim_user
    ON clinical.prescription_queue_claims (claimed_by, claimed_at);
