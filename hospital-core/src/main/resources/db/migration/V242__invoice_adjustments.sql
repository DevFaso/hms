-- V242 (round 16, QA D974): a discount, a waiver (gratuité, indigent
-- patient) or a credit note reversing a line of an issued invoice, each with
-- a mandatory reason. The row is the record of who lowered what was owed and
-- why; the invoice total is lowered by its amount. A credit note names the
-- line it reverses.
--
-- Ids, amounts and the clerk's reason only. Plain statements, no DO block.
-- Idempotent.

CREATE TABLE IF NOT EXISTS billing.invoice_adjustments (
    id                UUID            NOT NULL DEFAULT gen_random_uuid(),
    invoice_id        UUID            NOT NULL,
    invoice_item_id   UUID,
    adjustment_type   VARCHAR(20)     NOT NULL,
    amount            NUMERIC(12, 2)  NOT NULL,
    reason            VARCHAR(500)    NOT NULL,
    created_by        UUID,
    created_at        TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_ia_invoice FOREIGN KEY (invoice_id) REFERENCES billing.billing_invoices(id),
    CONSTRAINT fk_ia_invoice_item FOREIGN KEY (invoice_item_id) REFERENCES billing.invoice_items(id),
    CONSTRAINT chk_ia_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_ia_type CHECK (adjustment_type IN ('DISCOUNT', 'WAIVER', 'CREDIT_NOTE'))
);

CREATE INDEX IF NOT EXISTS idx_ia_invoice ON billing.invoice_adjustments (invoice_id);
CREATE INDEX IF NOT EXISTS idx_ia_invoice_item ON billing.invoice_adjustments (invoice_item_id);
