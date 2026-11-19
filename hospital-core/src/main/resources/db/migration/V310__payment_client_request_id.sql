-- V310 (billing-money go-live plan PR 11, scorecard M2, QA D2430): payment
-- idempotency.
--
-- A cash payment whose answer was lost on the way back was shown as failed
-- although it was saved, and the cashier's retry recorded it a second time.
-- Every money-in writer now takes the key the portal generates once per
-- dialog opening (client_request_id). A retry or a second submit carrying the
-- same key answers with the payment already recorded; no second row is
-- written. The same pattern as invoice create (V190, QA D94) and refunds
-- (V307).
--
--   billing.payment_transactions   staff payment, check-in copay
--   billing.pharmacy_payments      dispense checkout
--   clinical.pharmacy_sales        walk-in counter sale
--
-- payment_transactions had no hospital of its own (it is reached through the
-- invoice); hospital_id is added and backfilled from the invoice so the key
-- is unique per hospital, as on the other tables. NULL stays allowed: rows
-- written by older code paths keep working.
--
-- The key is optional: a request without one behaves as before. Unique per
-- hospital when present (partial unique index).
--
-- Plain idempotent DDL, no DO block.

ALTER TABLE billing.payment_transactions
    ADD COLUMN IF NOT EXISTS hospital_id UUID;
ALTER TABLE billing.payment_transactions
    ADD COLUMN IF NOT EXISTS client_request_id UUID;

UPDATE billing.payment_transactions pt
   SET hospital_id = bi.hospital_id
  FROM billing.billing_invoices bi
 WHERE bi.id = pt.invoice_id
   AND pt.hospital_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_pt_hospital_client_request
    ON billing.payment_transactions (hospital_id, client_request_id)
    WHERE client_request_id IS NOT NULL;

ALTER TABLE billing.pharmacy_payments
    ADD COLUMN IF NOT EXISTS client_request_id UUID;

CREATE UNIQUE INDEX IF NOT EXISTS uk_pp_hospital_client_request
    ON billing.pharmacy_payments (hospital_id, client_request_id)
    WHERE client_request_id IS NOT NULL;

ALTER TABLE clinical.pharmacy_sales
    ADD COLUMN IF NOT EXISTS client_request_id UUID;

CREATE UNIQUE INDEX IF NOT EXISTS uk_psale_hospital_client_request
    ON clinical.pharmacy_sales (hospital_id, client_request_id)
    WHERE client_request_id IS NOT NULL;
