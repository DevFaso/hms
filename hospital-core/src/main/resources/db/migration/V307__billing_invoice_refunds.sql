-- V307 (billing-money go-live plan PR 8, scorecard M3; fixes QA D1900): money
-- given back to a patient is recorded as money going OUT, with its own
-- approval, and never by rewriting a payment that came in.
--
-- Until now the ledger could only hold money coming in (payment_transactions
-- has CHECK (amount > 0) and no direction), no operation recorded a refund, and
-- the only workaround (reverse the payment, record a smaller one) misstated the
-- history and was audited as REFUND_ISSUED.
--
-- billing.invoice_refunds - one refund of one invoice, the outgoing entry of
--   the ledger. Whole francs, > 0. Its life:
--     REQUESTED  asked by a cashier, a receptionist, an accountant or a hospital
--                admin, with the method and the reason
--     APPROVED   decided by ANOTHER person holding HOSPITAL_ADMIN or ACCOUNTANT
--     REJECTED   decided by another such person, with a reason
--     PAID       handed to the patient, with the operator's reference (mobile
--                money, bank transfer: known only when the money is sent) and
--                a receipt number (-RB<n>, per invoice, assigned at payout);
--                counted as money out in the cash
--                closing of the day it was paid (paid_at), never on the day the
--                money came in, so a refund rewrites no past day's figures
--     CANCELLED  withdrawn before it was paid, with a reason
--   Owner decision D-3 (2026-11-15): no self-approval. The CHECK below holds it
--   in the database too (decided_by <> requested_by).
--   client_request_id makes a retried request idempotent per hospital.
--   cash_session_id is added by PR 10 (V309) with the cash sessions.
--
-- billing.billing_invoices.amount_refunded - what was paid out on the invoice.
--   amount_paid keeps every franc that came in (the original payment rows are
--   never changed); what the patient still has to get back is
--   amount_paid - total_amount - amount_refunded.
--
-- No card data: reference, reason, decision_reason and cancel_reason are
-- refused by the service when they hold a card number (Luhn); nothing here
-- stores one.
--
-- Idempotent (IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before each ADD). Plain
-- DDL, no DO block. Rollback: none declared (forward-only; a new table and an
-- additive column). V301-V319 are reserved for the billing-money plan.

ALTER TABLE billing.billing_invoices
    ADD COLUMN IF NOT EXISTS amount_refunded NUMERIC(12, 2) NOT NULL DEFAULT 0;

ALTER TABLE billing.billing_invoices DROP CONSTRAINT IF EXISTS ck_bi_amount_refunded;
ALTER TABLE billing.billing_invoices ADD CONSTRAINT ck_bi_amount_refunded
    CHECK (amount_refunded >= 0 AND amount_refunded = trunc(amount_refunded));

CREATE TABLE IF NOT EXISTS billing.invoice_refunds (
    id                     UUID           NOT NULL DEFAULT gen_random_uuid(),
    invoice_id             UUID           NOT NULL,
    hospital_id            UUID           NOT NULL,
    amount                 NUMERIC(12, 2) NOT NULL,
    method                 VARCHAR(30)    NOT NULL,
    mobile_money_operator  VARCHAR(20),
    reference              VARCHAR(120),
    reason                 VARCHAR(500)   NOT NULL,
    status                 VARCHAR(20)    NOT NULL,
    requested_by           UUID           NOT NULL,
    requested_at           TIMESTAMP      NOT NULL,
    decided_by             UUID,
    decided_at             TIMESTAMP,
    decision_reason        VARCHAR(500),
    paid_by                UUID,
    paid_at                TIMESTAMP,
    cancelled_by           UUID,
    cancelled_at           TIMESTAMP,
    cancel_reason          VARCHAR(500),
    client_request_id      UUID,
    receipt_seq            INTEGER,
    receipt_number         VARCHAR(80),
    created_at             TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at             TIMESTAMP      NOT NULL DEFAULT now(),

    CONSTRAINT pk_invoice_refunds          PRIMARY KEY (id),
    CONSTRAINT fk_refund_invoice           FOREIGN KEY (invoice_id) REFERENCES billing.billing_invoices (id),
    CONSTRAINT fk_refund_hospital          FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    CONSTRAINT ck_refund_amount            CHECK (amount > 0 AND amount = trunc(amount)),
    CONSTRAINT ck_refund_status            CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'PAID', 'CANCELLED')),
    -- Every PaymentMethod constant (EntitySchemaValidationIT); the service refuses INSURANCE, never money at a till.
    CONSTRAINT ck_refund_method            CHECK (method IN ('CASH', 'CREDIT_CARD', 'DEBIT_CARD', 'INSURANCE',
                                                             'BANK_TRANSFER', 'CHECK', 'CARD', 'MOBILE_MONEY', 'OTHER')),
    -- An operator only on a mobile-money refund, one of V308's values.
    CONSTRAINT ck_refund_operator          CHECK ((mobile_money_operator IS NULL
                                                   OR mobile_money_operator IN ('ORANGE_MONEY', 'MOOV_MONEY', 'AUTRE'))
                                                  AND (method = 'MOBILE_MONEY' OR mobile_money_operator IS NULL)),
    -- The operator and its reference are known when the money is sent: required once PAID (PR 8 review).
    CONSTRAINT ck_refund_mobile_money      CHECK (status <> 'PAID' OR method <> 'MOBILE_MONEY'
                                                  OR (mobile_money_operator IS NOT NULL
                                                      AND reference IS NOT NULL AND length(btrim(reference)) > 0)),
    CONSTRAINT ck_refund_transfer_ref      CHECK (status <> 'PAID' OR method <> 'BANK_TRANSFER'
                                                  OR (reference IS NOT NULL AND length(btrim(reference)) > 0)),
    -- A paid refund carries its receipt number, assigned once at payout and never renumbered.
    CONSTRAINT ck_refund_receipt           CHECK (status <> 'PAID'
                                                  OR (receipt_seq IS NOT NULL AND receipt_seq > 0
                                                      AND receipt_number IS NOT NULL)),
    CONSTRAINT ck_refund_reason            CHECK (length(btrim(reason)) > 0),
    -- D-3: the person who decides is never the person who asked.
    CONSTRAINT ck_refund_four_eyes         CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CONSTRAINT ck_refund_decided           CHECK (status NOT IN ('APPROVED', 'REJECTED', 'PAID')
                                                  OR (decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT ck_refund_rejected_reason   CHECK (status <> 'REJECTED'
                                                  OR (decision_reason IS NOT NULL AND length(btrim(decision_reason)) > 0)),
    CONSTRAINT ck_refund_paid              CHECK (status <> 'PAID' OR (paid_by IS NOT NULL AND paid_at IS NOT NULL)),
    CONSTRAINT ck_refund_cancelled         CHECK (status <> 'CANCELLED'
                                                  OR (cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL
                                                      AND cancel_reason IS NOT NULL AND length(btrim(cancel_reason)) > 0))
);

CREATE INDEX IF NOT EXISTS idx_refund_invoice ON billing.invoice_refunds (invoice_id);
CREATE INDEX IF NOT EXISTS idx_refund_hospital_status ON billing.invoice_refunds (hospital_id, status);
-- The cash closing reads one cashier's refunds paid out on one day.
CREATE INDEX IF NOT EXISTS idx_refund_paid_by_at ON billing.invoice_refunds (paid_by, paid_at)
    WHERE paid_at IS NOT NULL;
-- Receipt numbers per invoice: -RB1, -RB2, ... in payout order.
CREATE UNIQUE INDEX IF NOT EXISTS uq_refund_receipt_seq
    ON billing.invoice_refunds (invoice_id, receipt_seq)
    WHERE receipt_seq IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_refund_client_request
    ON billing.invoice_refunds (hospital_id, client_request_id)
    WHERE client_request_id IS NOT NULL;
