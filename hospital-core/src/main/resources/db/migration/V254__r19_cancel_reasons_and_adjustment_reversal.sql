-- V254 (QA D1069, D1105, round 19).
-- D1069: cancelling a paid pharmacy dispense asked for no reason, yet it
-- voided the payment and put the stock back. The pharmacist's reason is now
-- kept with the dispense (cancel_note; cancel_reason stays the enum of a
-- cancelled preparation).
-- D1105: a mistaken waiver on an invoice whose check-in copay was folded in
-- could only be undone by reversing the copay the patient really paid. An
-- adjustment (discount, waiver, credit note) can now be reversed on its own,
-- with a reason, when and by whom; the row is kept, never deleted.
-- Existing rows keep null: nothing was recorded for them.
-- Plain statements, no DO block. Idempotent.

ALTER TABLE clinical.dispenses
    ADD COLUMN IF NOT EXISTS cancel_note VARCHAR(500);

ALTER TABLE billing.invoice_adjustments
    ADD COLUMN IF NOT EXISTS reversed_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE billing.invoice_adjustments
    ADD COLUMN IF NOT EXISTS reversed_by UUID;
ALTER TABLE billing.invoice_adjustments
    ADD COLUMN IF NOT EXISTS reversal_reason VARCHAR(500);
