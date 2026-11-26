-- V383 (insurer claims plan PR 1, QA r77 D3984): the insurer's pre-authorisation number on an invoice line, and the
-- order the line bills.
--
-- 1. source_order_type / source_order_id: the clinical order a checkout line was posted for (an imaging order, a
--    procedure order), as BillableOrderType names it. With it, a pre-authorisation number recorded on the order after
--    the invoice was drafted or issued reaches the line. Plain columns, no foreign key: the orders live in several
--    tables. Both set or both null. Lines posted before this migration have no source: an order's name is not a key,
--    so none is guessed.
-- 2. pre_authorisation_number: the insurer's « bon / accord préalable » number for the act, copied from the order
--    when the line is posted and whenever the number is recorded on the order, or typed on the line by the desk or
--    billing. Who and when recorded it (recorded_by is null for a copy taken from the order at checkout). 120
--    characters, as imaging_orders.authorization_number. Never a money field: amounts are unchanged.
-- Plain statements, no DO block. Idempotent.

ALTER TABLE billing.invoice_items ADD COLUMN IF NOT EXISTS source_order_type VARCHAR(30);
ALTER TABLE billing.invoice_items ADD COLUMN IF NOT EXISTS source_order_id UUID;
ALTER TABLE billing.invoice_items ADD COLUMN IF NOT EXISTS pre_authorisation_number VARCHAR(120);
ALTER TABLE billing.invoice_items ADD COLUMN IF NOT EXISTS pre_authorisation_recorded_by UUID;
ALTER TABLE billing.invoice_items ADD COLUMN IF NOT EXISTS pre_authorisation_recorded_at TIMESTAMP;

ALTER TABLE billing.invoice_items DROP CONSTRAINT IF EXISTS ck_invoice_item_source_order;
ALTER TABLE billing.invoice_items
    ADD CONSTRAINT ck_invoice_item_source_order
    CHECK ((source_order_type IS NULL) = (source_order_id IS NULL)
           AND (source_order_type IS NULL OR source_order_type IN ('CONSULTATION', 'LAB_ORDER', 'IMAGING_ORDER',
               'PROCEDURE_ORDER', 'TRANSFUSION', 'BED_DAYS', 'PHARMACY_DISPENSE', 'DELIVERY')));

ALTER TABLE billing.invoice_items DROP CONSTRAINT IF EXISTS ck_invoice_item_pre_authorisation;
ALTER TABLE billing.invoice_items
    ADD CONSTRAINT ck_invoice_item_pre_authorisation
    CHECK (pre_authorisation_number IS NULL
           OR (btrim(pre_authorisation_number) <> '' AND pre_authorisation_recorded_at IS NOT NULL));

ALTER TABLE billing.invoice_items DROP CONSTRAINT IF EXISTS fk_invoice_item_pre_authorisation_recorder;
ALTER TABLE billing.invoice_items
    ADD CONSTRAINT fk_invoice_item_pre_authorisation_recorder
    FOREIGN KEY (pre_authorisation_recorded_by) REFERENCES security.users(id) ON DELETE NO ACTION;

-- The lines of one order, found when its number is recorded.
CREATE INDEX IF NOT EXISTS idx_item_source_order
    ON billing.invoice_items (source_order_id)
    WHERE source_order_id IS NOT NULL;
