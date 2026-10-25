-- V219 (QA D673): a lab order cancellation states why. The reason is kept on
-- the order; earlier cancels have none, so the column is nullable.
-- Plain statement, no DO block.

ALTER TABLE lab.lab_orders
    ADD COLUMN IF NOT EXISTS cancellation_reason VARCHAR(1000);
