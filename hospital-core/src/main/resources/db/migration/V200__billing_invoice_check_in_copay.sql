-- QA D210: the copay taken at check-in is billed as its own paid invoice.
-- Flag it so the visit's invoice, once issued, can take the copay payment over
-- as a credit instead of billing the patient a second time.
ALTER TABLE billing.billing_invoices
    ADD COLUMN IF NOT EXISTS check_in_copay BOOLEAN NOT NULL DEFAULT FALSE;
