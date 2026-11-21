-- V352 (QA r66 D2950): a pharmacy refund paid out is money out of a till.
--
-- clinical.pharmacy_refunds.cash_session_id - the cash session (V309) the
--   refund was handed over from: the payer's PHARMACY till of the day. V309
--   put this column on every other money row (invoice payments, dispense
--   payments, counter sales, invoice refunds); the pharmacy refund arrived
--   with V311 without it, so a refund paid at the counter left the till's
--   expected cash too high and the pharmacist had to explain a difference
--   that was not real, while the pharmacy reconciliation already counted it.
--   Nullable: a refund not yet paid has none.
--
-- Backfill: a refund already PAID is attached to the payer's PHARMACY session
--   of the day it was paid, when that session exists AND IS STILL OPEN. A till
--   already closed or reviewed was counted and explained without the refund:
--   attaching one now would move its expected cash after the fact, so its
--   figures stay as they were closed. A refund with no such session stays
--   without one (it is still counted by the reconciliation, by date). Stored
--   close counts (billing.cash_session_counts) are not touched.
--
-- Idempotent (IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before ADD; the UPDATE
-- only fills NULLs). Plain DDL and DML, no DO block.

ALTER TABLE clinical.pharmacy_refunds ADD COLUMN IF NOT EXISTS cash_session_id UUID;

ALTER TABLE clinical.pharmacy_refunds DROP CONSTRAINT IF EXISTS fk_prefund_cash_session;
ALTER TABLE clinical.pharmacy_refunds ADD CONSTRAINT fk_prefund_cash_session
    FOREIGN KEY (cash_session_id) REFERENCES billing.cash_sessions (id);

CREATE INDEX IF NOT EXISTS idx_prefund_cash_session ON clinical.pharmacy_refunds (cash_session_id);

UPDATE clinical.pharmacy_refunds r
   SET cash_session_id = s.id
  FROM billing.cash_sessions s
 WHERE r.cash_session_id IS NULL
   AND r.status = 'PAID'
   AND r.paid_by IS NOT NULL
   AND r.paid_at IS NOT NULL
   AND s.hospital_id = r.hospital_id
   AND s.cashier_user_id = r.paid_by
   AND s.till = 'PHARMACY'
   AND s.status = 'OPEN'
   AND s.business_date = CAST(r.paid_at AS DATE);
