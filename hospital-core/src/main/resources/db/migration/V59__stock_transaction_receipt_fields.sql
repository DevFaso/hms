-- FU-2 (P-06 backend follow-up): promote stock RECEIPT fields to first-class
-- columns on clinical.stock_transactions so reporting can query supplier /
-- expiry / cost directly instead of grepping the `reason` text. All columns
-- are nullable and additive — existing DISPENSE/TRANSFER/ADJUSTMENT/RETURN
-- rows are unaffected.
--
-- Rollback:
--   ALTER TABLE clinical.stock_transactions DROP COLUMN IF EXISTS unit_cost;
--   ALTER TABLE clinical.stock_transactions DROP COLUMN IF EXISTS expiry_date;
--   ALTER TABLE clinical.stock_transactions DROP COLUMN IF EXISTS po_reference;
--   ALTER TABLE clinical.stock_transactions DROP COLUMN IF EXISTS supplier;
--   ALTER TABLE clinical.stock_transactions DROP COLUMN IF EXISTS lot_number;

ALTER TABLE clinical.stock_transactions
    ADD COLUMN IF NOT EXISTS lot_number VARCHAR(120),
    ADD COLUMN IF NOT EXISTS supplier VARCHAR(200),
    ADD COLUMN IF NOT EXISTS po_reference VARCHAR(120),
    ADD COLUMN IF NOT EXISTS expiry_date DATE,
    ADD COLUMN IF NOT EXISTS unit_cost NUMERIC(12, 2);

-- A single index supports the common "what's expiring soon for receipts?" query.
CREATE INDEX IF NOT EXISTS idx_st_expiry_date ON clinical.stock_transactions(expiry_date);
