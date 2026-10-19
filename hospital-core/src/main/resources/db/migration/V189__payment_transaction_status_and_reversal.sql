-- =============================================================================
-- V189: payment ledger status and reversal (QA D140, D142).
--   status          CONFIRMED (counted in amount_paid), PENDING (declared by the
--                   patient, waits for a cashier), REJECTED (pending refused),
--                   REVERSED (confirmed, then reversed by billing staff).
--   confirmed_*     who confirmed a pending payment, and when.
--   reversed_*      who reversed a payment, when and why (mandatory reason).
-- Existing rows were all counted in amount_paid, so they default to CONFIRMED.
-- =============================================================================

ALTER TABLE billing.payment_transactions
    ADD COLUMN IF NOT EXISTS status          VARCHAR(20)  NOT NULL DEFAULT 'CONFIRMED',
    ADD COLUMN IF NOT EXISTS confirmed_by    UUID,
    ADD COLUMN IF NOT EXISTS confirmed_at    TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS reversed_by     UUID,
    ADD COLUMN IF NOT EXISTS reversed_at     TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS reversal_reason VARCHAR(500);

ALTER TABLE billing.payment_transactions
    DROP CONSTRAINT IF EXISTS chk_pt_status;
ALTER TABLE billing.payment_transactions
    ADD CONSTRAINT chk_pt_status CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED', 'REVERSED'));

CREATE INDEX IF NOT EXISTS idx_pt_invoice_status ON billing.payment_transactions (invoice_id, status);
