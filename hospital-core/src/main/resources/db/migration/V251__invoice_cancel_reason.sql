-- V251 (QA D1069, round 18): cancelling an issued invoice recorded no reason.
-- The edit form had no place to type one (the notes of an issued invoice are
-- locked, D1057) and the server took the status change on its own, so the
-- patient and the auditor saw only "Annulée". A cancellation now stores why,
-- when and by whom, as a discount, a waiver or a credit note does (D974).
-- Existing cancelled invoices keep null: nobody recorded a reason for them.
-- Plain statements, no DO block. Idempotent.

ALTER TABLE billing.billing_invoices
    ADD COLUMN IF NOT EXISTS cancel_reason VARCHAR(500);
ALTER TABLE billing.billing_invoices
    ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE billing.billing_invoices
    ADD COLUMN IF NOT EXISTS cancelled_by UUID;
