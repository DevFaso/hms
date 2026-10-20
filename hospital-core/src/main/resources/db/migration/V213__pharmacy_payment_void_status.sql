-- V213 (QA D404, D416, D228, D428): a pharmacy payment can be voided when its dispense is
-- cancelled. Before this, cancelling a paid dispense returned the stock and
-- left the payment counted, with no column to say otherwise.
-- Plain statements, no DO block.

ALTER TABLE billing.pharmacy_payments
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'POSTED';
ALTER TABLE billing.pharmacy_payments
    ADD COLUMN IF NOT EXISTS voided_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE billing.pharmacy_payments
    ADD COLUMN IF NOT EXISTS voided_by UUID;
ALTER TABLE billing.pharmacy_payments
    ADD COLUMN IF NOT EXISTS void_reason VARCHAR(255);

ALTER TABLE billing.pharmacy_payments
    DROP CONSTRAINT IF EXISTS chk_pp_status;
ALTER TABLE billing.pharmacy_payments
    ADD CONSTRAINT chk_pp_status CHECK (status IN ('POSTED', 'VOIDED'));

-- A payment left on a dispense that is already cancelled is voided now, so
-- the patient's history and the till stop counting it.
UPDATE billing.pharmacy_payments p
   SET status = 'VOIDED',
       voided_at = d.updated_at,
       void_reason = 'Dispense cancelled before voids were recorded',
       updated_at = now()
  FROM clinical.dispenses d
 WHERE d.id = p.dispense_id
   AND d.status = 'CANCELLED'
   AND p.status = 'POSTED';

-- QA D416: a fill from a lot whose dispense was saved without its catalog
-- medication (the dispensing screen sent only the lot) had no price and
-- could never be paid. Its medication is the lot's.
UPDATE clinical.dispenses d
   SET medication_catalog_id = ii.medication_catalog_id
  FROM clinical.stock_lots l
  JOIN clinical.inventory_items ii ON ii.id = l.inventory_item_id
 WHERE d.stock_lot_id = l.id
   AND d.medication_catalog_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_pp_dispense_status ON billing.pharmacy_payments (dispense_id, status);

-- QA D228 (not fixed in round 4): a receipt printed later showed the
-- invoice's total as it is now. The total when the money was received is
-- kept on the payment row; rows from before keep the old behaviour (null).
ALTER TABLE billing.payment_transactions
    ADD COLUMN IF NOT EXISTS invoice_total_at_receipt NUMERIC(12, 2);

-- QA D428: a check-in copay folded into the visit invoice showed only as
-- "cancelled, 0 paid". It now names the invoice it was credited to.
ALTER TABLE billing.billing_invoices
    ADD COLUMN IF NOT EXISTS credited_to_invoice_id UUID;
ALTER TABLE billing.billing_invoices
    DROP CONSTRAINT IF EXISTS fk_bi_credited_to;
ALTER TABLE billing.billing_invoices
    ADD CONSTRAINT fk_bi_credited_to FOREIGN KEY (credited_to_invoice_id)
        REFERENCES billing.billing_invoices (id);

-- Copays already folded: their confirmed payment rows sit on the visit
-- invoice of the same encounter.
UPDATE billing.billing_invoices c
   SET credited_to_invoice_id = v.id
  FROM billing.billing_invoices v
 WHERE c.check_in_copay = TRUE
   AND c.status = 'CANCELLED'
   AND c.credited_to_invoice_id IS NULL
   AND v.encounter_id = c.encounter_id
   AND v.check_in_copay = FALSE
   AND v.id = (SELECT v2.id FROM billing.billing_invoices v2
                WHERE v2.encounter_id = c.encounter_id AND v2.check_in_copay = FALSE
                  AND v2.status <> 'CANCELLED'
                ORDER BY v2.created_at LIMIT 1);
