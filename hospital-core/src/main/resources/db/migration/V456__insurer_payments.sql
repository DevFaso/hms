-- V456 (insurer claims plan PR 3, QA D3983): what a payer remits, and the
-- bordereau lines each remittance settles.
--
-- billing.insurer_payments - one remittance of one payer at one hospital:
--   amount (whole francs), the date it was received, the method (bank
--   transfer, cheque, mobile money, cash; never a card), its reference and a
--   note. RECORDED, or REVERSED with a reason by another person than the one
--   who recorded it. It is never a till movement: no cash session column.
--   client_request_id makes a retried request idempotent per hospital; one
--   live payment per payer and reference.
--
-- billing.insurer_payment_allocations - the part of a payment put on one
--   bordereau line. Insert-only; voided_at is set when the payment is
--   reversed. The live allocations of a payment never exceed its amount; the
--   rest stays on the payment as money to allocate.
--
-- No PHI: ids, amounts, a bank reference. Idempotent (IF NOT EXISTS). Plain
-- DDL, no DO block. Rollback: none declared (forward-only, new tables).

CREATE TABLE IF NOT EXISTS billing.insurer_payments (
    id                  UUID           NOT NULL DEFAULT gen_random_uuid(),
    hospital_id         UUID           NOT NULL,
    payer_id            UUID           NOT NULL,
    amount              NUMERIC(14, 2) NOT NULL,
    received_date       DATE           NOT NULL,
    method              VARCHAR(20)    NOT NULL,
    reference           VARCHAR(120)   NOT NULL,
    note                VARCHAR(500),
    status              VARCHAR(20)    NOT NULL,
    recorded_by         UUID           NOT NULL,
    recorded_at         TIMESTAMP      NOT NULL,
    reversed_by         UUID,
    reversed_at         TIMESTAMP,
    reversal_reason     VARCHAR(500),
    client_request_id   UUID           NOT NULL,
    created_at          TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP      NOT NULL DEFAULT now(),

    CONSTRAINT pk_insurer_payments          PRIMARY KEY (id),
    CONSTRAINT uq_insurer_payment_id_hosp   UNIQUE (id, hospital_id),
    CONSTRAINT fk_insurer_payment_hospital  FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    CONSTRAINT fk_insurer_payment_payer     FOREIGN KEY (payer_id, hospital_id)
        REFERENCES billing.payers (id, hospital_id),
    CONSTRAINT ck_insurer_payment_amount    CHECK (amount > 0 AND amount = trunc(amount)),
    CONSTRAINT ck_insurer_payment_method    CHECK (method IN ('BANK_TRANSFER', 'CHEQUE', 'MOBILE_MONEY', 'CASH')),
    CONSTRAINT ck_insurer_payment_status    CHECK (status IN ('RECORDED', 'REVERSED')),
    CONSTRAINT ck_insurer_payment_reference CHECK (length(btrim(reference)) > 0),
    CONSTRAINT ck_insurer_payment_reversed  CHECK (status <> 'REVERSED'
                                                   OR (reversed_by IS NOT NULL AND reversed_at IS NOT NULL
                                                       AND reversed_by <> recorded_by
                                                       AND reversal_reason IS NOT NULL
                                                       AND length(btrim(reversal_reason)) > 0))
);

CREATE INDEX IF NOT EXISTS idx_insurer_payment_hospital_payer ON billing.insurer_payments (hospital_id, payer_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_insurer_payment_client_request
    ON billing.insurer_payments (hospital_id, client_request_id);
-- The same remittance is not recorded twice under another key.
CREATE UNIQUE INDEX IF NOT EXISTS uq_insurer_payment_reference
    ON billing.insurer_payments (hospital_id, payer_id, lower(btrim(reference)))
    WHERE status = 'RECORDED';

CREATE TABLE IF NOT EXISTS billing.insurer_payment_allocations (
    id              UUID           NOT NULL DEFAULT gen_random_uuid(),
    hospital_id     UUID           NOT NULL,
    payment_id      UUID           NOT NULL,
    claim_line_id   UUID           NOT NULL,
    share_id        UUID           NOT NULL,
    amount          NUMERIC(12, 2) NOT NULL,
    allocated_by    UUID           NOT NULL,
    allocated_at    TIMESTAMP      NOT NULL,
    voided_at       TIMESTAMP,
    created_at      TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP      NOT NULL DEFAULT now(),

    CONSTRAINT pk_insurer_payment_allocations PRIMARY KEY (id),
    CONSTRAINT fk_insurer_alloc_hospital      FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    CONSTRAINT fk_insurer_alloc_payment       FOREIGN KEY (payment_id, hospital_id)
        REFERENCES billing.insurer_payments (id, hospital_id),
    CONSTRAINT fk_insurer_alloc_line          FOREIGN KEY (claim_line_id, hospital_id)
        REFERENCES billing.insurer_claim_lines (id, hospital_id),
    CONSTRAINT fk_insurer_alloc_share         FOREIGN KEY (share_id) REFERENCES billing.invoice_payer_shares (id),
    CONSTRAINT ck_insurer_alloc_amount        CHECK (amount > 0 AND amount = trunc(amount))
);

CREATE INDEX IF NOT EXISTS idx_insurer_alloc_payment ON billing.insurer_payment_allocations (payment_id);
CREATE INDEX IF NOT EXISTS idx_insurer_alloc_line ON billing.insurer_payment_allocations (claim_line_id);
