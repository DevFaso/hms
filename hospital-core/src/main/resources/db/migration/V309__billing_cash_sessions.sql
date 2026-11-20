-- V309 (billing-money go-live plan PR 10, scorecard M5/M3/M1; QA r65 D2325,
-- D2326, D2714, D2672, D2702, D2700): cash sessions with a counted close and a
-- lock, payment reversals through the two-person refund approval, and lab-test
-- price links on the test's stable identity.
--
-- billing.cash_sessions - one cashier, one till (BILLING, RECEPTION,
--   PHARMACY), one business day (Africa/Ouagadougou) at one hospital. Opened
--   by that cashier's first money movement of the day. OPEN -> CLOSED (counted
--   per method, signed) -> REVIEWED (a supervisor's note). A CLOSED session
--   takes no new movement; a hospital admin or an accountant may reopen it
--   with a reason (close_round counts the closes; earlier counts are kept).
--   UNIQUE (hospital, cashier, till, day): two movements at the same instant
--   meet one session.
--
-- billing.cash_session_counts - one row per method (and mobile-money
--   operator) per close round: expected (money in - money out), counted,
--   difference, and the explanation a non-zero difference requires.
--
-- cash_session_id on every money row (invoice payments, pharmacy dispense
--   payments, pharmacy counter sales, refunds paid out): the session the
--   movement was taken in. Nullable: rows written before this release have
--   none and stay readable by date.
--
-- billing.invoice_refunds.reversed_payment_id - QA D2702: a payment reversal
--   is now a refund of that payment, asked for with a reason, approved by
--   ANOTHER hospital admin or accountant (the V307 CHECK decided_by <>
--   requested_by holds here too) and paid out at a till as money out. One
--   live reversal per payment (partial unique index).
-- billing.payment_transactions.reversal_refund_id - the paid-out refund that
--   reversed the row, so the close keeps the money in on the day it came in
--   and counts the outflow on the day it left.
--
-- QA D2700: a price-list link to a lab test pointed at one VERSION of the
--   definition; the lab's next version unpriced the test. Links are now the
--   lineage's first version (the root of supersedes_definition_id). The
--   existing links are moved to their root, unless another active item of
--   the hospital already holds that root (left as is; the price list flags it).
--
-- Idempotent (IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before ADD, the link
-- UPDATE is a no-op once applied). Plain DDL and DML, no DO block.

CREATE TABLE IF NOT EXISTS billing.cash_sessions (
    id                UUID          NOT NULL DEFAULT gen_random_uuid(),
    hospital_id       UUID          NOT NULL,
    cashier_user_id   UUID          NOT NULL,
    till              VARCHAR(20)   NOT NULL,
    business_date     DATE          NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    opened_at         TIMESTAMP     NOT NULL,
    close_round       INTEGER       NOT NULL DEFAULT 0,
    closed_at         TIMESTAMP,
    closed_by         UUID,
    reviewed_by       UUID,
    reviewed_at       TIMESTAMP,
    review_note       VARCHAR(500),
    reopened_by       UUID,
    reopened_at       TIMESTAMP,
    reopen_reason     VARCHAR(500),
    created_at        TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_cash_sessions          PRIMARY KEY (id),
    CONSTRAINT fk_cash_session_hospital  FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    CONSTRAINT ck_cash_session_till      CHECK (till IN ('BILLING', 'RECEPTION', 'PHARMACY')),
    CONSTRAINT ck_cash_session_status    CHECK (status IN ('OPEN', 'CLOSED', 'REVIEWED')),
    CONSTRAINT uq_cash_session_day       UNIQUE (hospital_id, cashier_user_id, till, business_date)
);

CREATE INDEX IF NOT EXISTS idx_cash_session_hospital_day
    ON billing.cash_sessions (hospital_id, business_date);

CREATE TABLE IF NOT EXISTS billing.cash_session_counts (
    id                     UUID           NOT NULL DEFAULT gen_random_uuid(),
    session_id             UUID           NOT NULL,
    close_round            INTEGER        NOT NULL,
    method                 VARCHAR(30)    NOT NULL,
    mobile_money_operator  VARCHAR(20),
    expected_amount        NUMERIC(12, 2) NOT NULL,
    counted_amount         NUMERIC(12, 2) NOT NULL,
    difference             NUMERIC(12, 2) NOT NULL,
    explanation            VARCHAR(500),
    counted_by             UUID           NOT NULL,
    created_at             TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at             TIMESTAMP      NOT NULL DEFAULT now(),

    CONSTRAINT pk_cash_session_counts     PRIMARY KEY (id),
    CONSTRAINT fk_cash_count_session      FOREIGN KEY (session_id) REFERENCES billing.cash_sessions (id),
    CONSTRAINT ck_cash_count_whole        CHECK (counted_amount >= 0 AND counted_amount = trunc(counted_amount))
);

CREATE INDEX IF NOT EXISTS idx_cash_count_session ON billing.cash_session_counts (session_id, close_round);

ALTER TABLE billing.payment_transactions ADD COLUMN IF NOT EXISTS cash_session_id UUID;
ALTER TABLE billing.payment_transactions ADD COLUMN IF NOT EXISTS reversal_refund_id UUID;
ALTER TABLE billing.pharmacy_payments ADD COLUMN IF NOT EXISTS cash_session_id UUID;
ALTER TABLE clinical.pharmacy_sales ADD COLUMN IF NOT EXISTS cash_session_id UUID;
ALTER TABLE billing.invoice_refunds ADD COLUMN IF NOT EXISTS cash_session_id UUID;
ALTER TABLE billing.invoice_refunds ADD COLUMN IF NOT EXISTS reversed_payment_id UUID;

ALTER TABLE billing.payment_transactions DROP CONSTRAINT IF EXISTS fk_pt_cash_session;
ALTER TABLE billing.payment_transactions ADD CONSTRAINT fk_pt_cash_session
    FOREIGN KEY (cash_session_id) REFERENCES billing.cash_sessions (id);
ALTER TABLE billing.pharmacy_payments DROP CONSTRAINT IF EXISTS fk_pp_cash_session;
ALTER TABLE billing.pharmacy_payments ADD CONSTRAINT fk_pp_cash_session
    FOREIGN KEY (cash_session_id) REFERENCES billing.cash_sessions (id);
ALTER TABLE clinical.pharmacy_sales DROP CONSTRAINT IF EXISTS fk_psale_cash_session;
ALTER TABLE clinical.pharmacy_sales ADD CONSTRAINT fk_psale_cash_session
    FOREIGN KEY (cash_session_id) REFERENCES billing.cash_sessions (id);
ALTER TABLE billing.invoice_refunds DROP CONSTRAINT IF EXISTS fk_refund_cash_session;
ALTER TABLE billing.invoice_refunds ADD CONSTRAINT fk_refund_cash_session
    FOREIGN KEY (cash_session_id) REFERENCES billing.cash_sessions (id);
ALTER TABLE billing.invoice_refunds DROP CONSTRAINT IF EXISTS fk_refund_reversed_payment;
ALTER TABLE billing.invoice_refunds ADD CONSTRAINT fk_refund_reversed_payment
    FOREIGN KEY (reversed_payment_id) REFERENCES billing.payment_transactions (id);

CREATE INDEX IF NOT EXISTS idx_pt_cash_session ON billing.payment_transactions (cash_session_id);
CREATE INDEX IF NOT EXISTS idx_pp_cash_session ON billing.pharmacy_payments (cash_session_id);
CREATE INDEX IF NOT EXISTS idx_psale_cash_session ON clinical.pharmacy_sales (cash_session_id);
CREATE INDEX IF NOT EXISTS idx_refund_cash_session ON billing.invoice_refunds (cash_session_id);

-- One live reversal per payment: asked, approved or paid.
CREATE UNIQUE INDEX IF NOT EXISTS uq_refund_live_reversal
    ON billing.invoice_refunds (reversed_payment_id)
    WHERE reversed_payment_id IS NOT NULL AND status IN ('REQUESTED', 'APPROVED', 'PAID');

-- QA D2700: lab-test price links move to the first version of the test's lineage.
WITH RECURSIVE lineage AS (
    SELECT d.id AS start_id, d.id AS cur_id, d.supersedes_definition_id AS parent_id, 0 AS depth
    FROM lab.lab_test_definitions d
    UNION ALL
    SELECT l.start_id, p.id, p.supersedes_definition_id, l.depth + 1
    FROM lineage l
    JOIN lab.lab_test_definitions p ON p.id = l.parent_id
    WHERE l.depth < 50
),
roots AS (
    SELECT DISTINCT ON (start_id) start_id, cur_id AS root_id
    FROM lineage
    ORDER BY start_id, depth DESC
),
moves AS (
    SELECT i.id, i.hospital_id, i.active, r.root_id
    FROM billing.price_list_items i
    JOIN roots r ON i.link_key = CAST(r.start_id AS VARCHAR)
    WHERE i.link_type = 'LAB_TEST_DEFINITION' AND r.root_id <> r.start_id
)
UPDATE billing.price_list_items t
SET link_key = CAST(m.root_id AS VARCHAR), updated_at = now()
FROM moves m
WHERE t.id = m.id
  AND (NOT m.active OR (
        NOT EXISTS (SELECT 1 FROM billing.price_list_items o
                    WHERE o.id <> t.id AND o.hospital_id = t.hospital_id AND o.active
                      AND o.link_type = 'LAB_TEST_DEFINITION' AND o.link_key = CAST(m.root_id AS VARCHAR))
    AND NOT EXISTS (SELECT 1 FROM moves m2
                    WHERE m2.id <> m.id AND m2.hospital_id = m.hospital_id AND m2.active
                      AND m2.root_id = m.root_id)));
