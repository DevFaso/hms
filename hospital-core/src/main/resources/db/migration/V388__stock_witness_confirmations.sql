-- V388: the witness of a controlled-drug movement confirms (QA D2679, r65 -> r77).
--
-- The witness of a controlled-drug stock movement was a name the performer picked in a list: the
-- witness never acted. A witness now confirms with their own password or authenticator code, and
-- a controlled movement naming them is recorded only while that confirmation is unexpired.
--
-- 1. One row per confirmation: the witness, the performer they confirmed for, the hospital, the
--    method (TOTP or PASSWORD) and the two times. Ids only; the secret is never stored.
-- 2. Rows are kept (no sweep): they are the record that the second person acted. "Still good" is
--    expires_at > now, read through idx_stock_witness_confirmation_lookup.
-- 3. created_at / updated_at: the entity extends BaseEntity.
-- 4. A user or a hospital is never hard-deleted while it has confirmations (no cascade): the
--    record outlives a deactivated account.
--
-- New, empty table: no backfill, no CHECK constraints, no DO blocks.
-- Forward-only; harmless to the previous application version, which never reads it.

CREATE TABLE IF NOT EXISTS clinical.stock_witness_confirmations (
    id                   UUID PRIMARY KEY,
    witness_user_id      UUID NOT NULL,
    performed_by_user_id UUID NOT NULL,
    hospital_id          UUID NOT NULL,
    method               VARCHAR(20) NOT NULL,
    confirmed_at         TIMESTAMP NOT NULL,
    expires_at           TIMESTAMP NOT NULL,
    created_at           TIMESTAMP NOT NULL,
    updated_at           TIMESTAMP NOT NULL,
    CONSTRAINT fk_stock_witness_confirmation_witness FOREIGN KEY (witness_user_id)
        REFERENCES "security".users (id),
    CONSTRAINT fk_stock_witness_confirmation_performer FOREIGN KEY (performed_by_user_id)
        REFERENCES "security".users (id),
    CONSTRAINT fk_stock_witness_confirmation_hospital FOREIGN KEY (hospital_id)
        REFERENCES hospital.hospitals (id)
);

CREATE INDEX IF NOT EXISTS idx_stock_witness_confirmation_lookup
    ON clinical.stock_witness_confirmations (witness_user_id, performed_by_user_id, hospital_id, expires_at);
