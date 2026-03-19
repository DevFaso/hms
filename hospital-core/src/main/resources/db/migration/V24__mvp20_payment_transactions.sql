-- =============================================================================
-- V24: MVP 20 — Payment Transaction Ledger
-- Adds payment_transactions table to billing schema for granular payment
-- tracking on each BillingInvoice.
-- =============================================================================

CREATE TABLE IF NOT EXISTS billing.payment_transactions (
    id                UUID            NOT NULL DEFAULT gen_random_uuid(),
    invoice_id        UUID            NOT NULL,
    amount            NUMERIC(12, 2)  NOT NULL,
    payment_date      DATE            NOT NULL,
    payment_method    VARCHAR(30)     NOT NULL,
    reference_number  VARCHAR(120),
    recorded_by       UUID,
    notes             VARCHAR(1024),
    created_at        TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_pt_invoice FOREIGN KEY (invoice_id) REFERENCES billing.billing_invoices(id),
    CONSTRAINT chk_pt_amount_positive CHECK (amount > 0)
);

CREATE INDEX IF NOT EXISTS idx_pt_invoice ON billing.payment_transactions (invoice_id);
CREATE INDEX IF NOT EXISTS idx_pt_payment_date ON billing.payment_transactions (payment_date);
CREATE INDEX IF NOT EXISTS idx_pt_method ON billing.payment_transactions (payment_method);
