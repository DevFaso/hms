-- V308 (billing-money go-live plan PR 9, scorecard M5): the mobile-money
-- operator of a payment, so the cash close can total Orange Money and Moov
-- Money apart.
--
-- There is no operator integration (plan section 1.5): a Mobile Money payment
-- is recorded by hand by the cashier with the operator's transaction number,
-- and reconciled against the operator's merchant statement. The new column
-- only says which operator the money went through:
--
--   ORANGE_MONEY  Orange Money
--   MOOV_MONEY    Moov Money
--   AUTRE         another operator
--
-- It is added to the three money-in ledgers that take Mobile Money:
--   billing.payment_transactions   invoice payments, check-in copays,
--                                  patient declarations
--   billing.pharmacy_payments     dispense checkout
--   clinical.pharmacy_sales        walk-in counter sales
--
-- NULL on every older row (shown as "Opérateur non précisé"), and NULL for any
-- method other than Mobile Money. The service requires it for a new Mobile
-- Money payment taken by staff; a patient declaration from the mobile apps
-- (which do not send it yet) stays NULL.
--
-- Plain idempotent DDL, no DO block.

ALTER TABLE billing.payment_transactions
    ADD COLUMN IF NOT EXISTS mobile_money_operator VARCHAR(20);
ALTER TABLE billing.payment_transactions
    DROP CONSTRAINT IF EXISTS chk_pt_mobile_money_operator;
ALTER TABLE billing.payment_transactions
    ADD CONSTRAINT chk_pt_mobile_money_operator CHECK (
        mobile_money_operator IS NULL
        OR (mobile_money_operator IN ('ORANGE_MONEY', 'MOOV_MONEY', 'AUTRE')
            AND payment_method = 'MOBILE_MONEY'));

ALTER TABLE billing.pharmacy_payments
    ADD COLUMN IF NOT EXISTS mobile_money_operator VARCHAR(20);
ALTER TABLE billing.pharmacy_payments
    DROP CONSTRAINT IF EXISTS chk_pharmacy_payments_mobile_money_operator;
ALTER TABLE billing.pharmacy_payments
    ADD CONSTRAINT chk_pharmacy_payments_mobile_money_operator CHECK (
        mobile_money_operator IS NULL
        OR (mobile_money_operator IN ('ORANGE_MONEY', 'MOOV_MONEY', 'AUTRE')
            AND payment_method = 'MOBILE_MONEY'));

ALTER TABLE clinical.pharmacy_sales
    ADD COLUMN IF NOT EXISTS mobile_money_operator VARCHAR(20);
ALTER TABLE clinical.pharmacy_sales
    DROP CONSTRAINT IF EXISTS chk_pharmacy_sales_mobile_money_operator;
ALTER TABLE clinical.pharmacy_sales
    ADD CONSTRAINT chk_pharmacy_sales_mobile_money_operator CHECK (
        mobile_money_operator IS NULL
        OR (mobile_money_operator IN ('ORANGE_MONEY', 'MOOV_MONEY', 'AUTRE')
            AND payment_method = 'MOBILE_MONEY'));
