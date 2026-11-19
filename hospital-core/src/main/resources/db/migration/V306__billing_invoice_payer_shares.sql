-- V306 (billing-money go-live plan PR 7, scorecard M1): a payer's coverage
-- applied to the invoice. Until now the payer catalogue (V305) was read by
-- nothing at invoicing, and the invoice API reported coverage figures that
-- were always 0.
--
-- billing.invoice_payer_shares - what one payer plan owes on one invoice,
--   through one of the patient's coverages (clinical.patient_insurances). It is
--   computed when the invoice is issued, per line: FLOOR(line x rate / 100),
--   capped by the per-act ceiling, then by the per-invoice ceiling, then by
--   what is left of the annual ceiling per beneficiary in coverage_year (the
--   calendar year of the service date, owner default D-13). Whole francs.
--   Its life (owner decisions D-5, D-6, plan section 3.4):
--     OPEN       owed by the payer, not claimed yet
--     CLAIMED    sent to the payer on a bordereau
--     PAID       settled by the payer
--     REJECTED   refused by the payer, with its reason: the amount moves back
--                to the patient; the row keeps the original amount
--     CANCELLED  withdrawn: the invoice was cancelled, or a credit note
--                changed a line and a new share replaced this one
--   Append-only for the amount: a change of share is a new row, never an
--   UPDATE of amount. Only the status and its decision columns move.
--   At most one live (OPEN / CLAIMED / PAID) share per invoice and coverage.
--   The annual ceiling sums the shares of one coverage and year that are not
--   REJECTED or CANCELLED; the service computes it under a row lock on the
--   patient_insurances row, so two invoices cannot both use the same remainder.
--
-- billing.billing_invoices.payer_share_total - the sum of the invoice's live
--   shares. What the patient owes is total_amount - payer_share_total - what
--   the hospital holds (amount_paid - amount_refunded), never below zero.
--
-- billing.invoice_items.payer_share_amount - the live share's part on each
--   line (0 when the line is not covered), shown as "Part assureur".
--
-- No PHI: ids and amounts only; rejection_reason is the payer's motif as
-- typed, refused by the service when it holds a card number.
--
-- Idempotent (IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before each ADD). Plain
-- DDL, no DO block. Rollback: none declared (forward-only; a new table and
-- additive columns with defaults). V301-V319 are reserved for the
-- billing-money plan.

ALTER TABLE billing.billing_invoices
    ADD COLUMN IF NOT EXISTS payer_share_total NUMERIC(12, 2) NOT NULL DEFAULT 0;

ALTER TABLE billing.billing_invoices DROP CONSTRAINT IF EXISTS ck_bi_payer_share_total;
ALTER TABLE billing.billing_invoices ADD CONSTRAINT ck_bi_payer_share_total
    CHECK (payer_share_total >= 0 AND payer_share_total = trunc(payer_share_total));

ALTER TABLE billing.invoice_items
    ADD COLUMN IF NOT EXISTS payer_share_amount NUMERIC(12, 2) NOT NULL DEFAULT 0;

ALTER TABLE billing.invoice_items DROP CONSTRAINT IF EXISTS ck_item_payer_share_amount;
ALTER TABLE billing.invoice_items ADD CONSTRAINT ck_item_payer_share_amount
    CHECK (payer_share_amount >= 0 AND payer_share_amount = trunc(payer_share_amount));

CREATE TABLE IF NOT EXISTS billing.invoice_payer_shares (
    id                     UUID           NOT NULL DEFAULT gen_random_uuid(),
    invoice_id             UUID           NOT NULL,
    hospital_id            UUID           NOT NULL,
    patient_insurance_id   UUID           NOT NULL,
    payer_plan_id          UUID           NOT NULL,
    amount                 NUMERIC(12, 2) NOT NULL,
    coverage_year          INTEGER        NOT NULL,
    status                 VARCHAR(20)    NOT NULL,
    rejection_reason       VARCHAR(500),
    decided_by             UUID,
    decided_at             TIMESTAMP,
    created_by             UUID,
    created_at             TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at             TIMESTAMP      NOT NULL DEFAULT now(),

    CONSTRAINT pk_invoice_payer_shares      PRIMARY KEY (id),
    CONSTRAINT fk_payer_share_invoice       FOREIGN KEY (invoice_id) REFERENCES billing.billing_invoices (id),
    CONSTRAINT fk_payer_share_hospital      FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    CONSTRAINT fk_payer_share_insurance     FOREIGN KEY (patient_insurance_id)
        REFERENCES clinical.patient_insurances (id),
    -- The plan is one of the invoice's hospital (payer_plans is UNIQUE (id, hospital_id), V305).
    CONSTRAINT fk_payer_share_plan          FOREIGN KEY (payer_plan_id, hospital_id)
        REFERENCES billing.payer_plans (id, hospital_id),
    CONSTRAINT ck_payer_share_amount        CHECK (amount >= 0 AND amount = trunc(amount)),
    CONSTRAINT ck_payer_share_year          CHECK (coverage_year BETWEEN 2000 AND 9999),
    CONSTRAINT ck_payer_share_status        CHECK (status IN ('OPEN', 'CLAIMED', 'PAID', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_payer_share_rejected      CHECK (status <> 'REJECTED'
                                                   OR (rejection_reason IS NOT NULL
                                                       AND length(btrim(rejection_reason)) > 0)),
    CONSTRAINT ck_payer_share_decided       CHECK (status = 'OPEN' OR (decided_by IS NOT NULL AND decided_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_payer_share_invoice ON billing.invoice_payer_shares (invoice_id);
CREATE INDEX IF NOT EXISTS idx_payer_share_hospital_status ON billing.invoice_payer_shares (hospital_id, status);
-- The annual ceiling sums one coverage's shares in one year.
CREATE INDEX IF NOT EXISTS idx_payer_share_insurance_year
    ON billing.invoice_payer_shares (patient_insurance_id, coverage_year);
-- One live share per invoice and coverage.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payer_share_live
    ON billing.invoice_payer_shares (invoice_id, patient_insurance_id)
    WHERE status IN ('OPEN', 'CLAIMED', 'PAID');
