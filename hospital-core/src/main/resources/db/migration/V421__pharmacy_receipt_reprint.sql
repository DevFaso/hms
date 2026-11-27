-- =====================================================================
-- V421 — QA r81 D4186: a pharmacy receipt can be opened again and
-- reprinted with the prices first charged.
--
-- 1. billing.pharmacy_payments.charged_unit_price / charged_line_total:
--    the selling price of one unit and the line total when the payment
--    was taken. A later read prints them, never the list price of the
--    day it is read. Null on payments recorded before this migration
--    (their reprint shows the total paid and no price line).
-- 2. clinical.pharmacy_sales.receipt_number: the readable receipt number
--    of a counter sale, from the hospital's pharmacy receipt counter
--    (the one the checkout's payments use). Null on earlier sales.
--
-- Additive and idempotent (IF NOT EXISTS). Rollback: none declared.
-- =====================================================================

ALTER TABLE billing.pharmacy_payments
    ADD COLUMN IF NOT EXISTS charged_unit_price NUMERIC(12, 2);

ALTER TABLE billing.pharmacy_payments
    ADD COLUMN IF NOT EXISTS charged_line_total NUMERIC(12, 2);

ALTER TABLE clinical.pharmacy_sales
    ADD COLUMN IF NOT EXISTS receipt_number VARCHAR(40);
