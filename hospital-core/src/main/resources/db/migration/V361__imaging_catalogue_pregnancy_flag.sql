-- =====================================================================
-- V361 (QA r70 D3450 High, D3254): the imaging order names its exam in
-- the hospital's imaging catalogue (the IMAGING lines of the price list),
-- and a catalogue exam says whether it may reveal a pregnancy.
--
--   billing.price_list_items
--     imaging_body_region          the body region of an imaging exam
--                                  (structured, shown on the order form).
--     may_reveal_pregnancy         TRUE  = never shown to a family proxy;
--                                  FALSE = cleared by the hospital admin or
--                                          a radiologist;
--                                  NULL  = nobody decided: the application
--                                          reads the exam's name, and fails
--                                          closed for an ultrasound or a
--                                          pelvic / abdominal study of a
--                                          female patient aged 10 to 55.
--     may_reveal_pregnancy_set_by  who decided, and when (NULL = nobody).
--     may_reveal_pregnancy_set_at
--
--   clinical.imaging_orders
--     catalogue_item_id            the price-list item the exam was picked
--                                  from (NULL = a free-text order, as every
--                                  existing row). The order is priced by
--                                  this item, whatever its wording.
--     catalogue_code               the item's code when the order was
--                                  placed (a copy, for reading).
--
-- No existing row changes: every new column is NULL, which is the
-- behaviour before this migration plus the fail-closed rule in code.
-- Additive and idempotent: IF NOT EXISTS, no DO block, no backfill.
-- =====================================================================

ALTER TABLE billing.price_list_items
    ADD COLUMN IF NOT EXISTS imaging_body_region VARCHAR(150);

ALTER TABLE billing.price_list_items
    ADD COLUMN IF NOT EXISTS may_reveal_pregnancy BOOLEAN;

ALTER TABLE billing.price_list_items
    ADD COLUMN IF NOT EXISTS may_reveal_pregnancy_set_by UUID;

ALTER TABLE billing.price_list_items
    ADD COLUMN IF NOT EXISTS may_reveal_pregnancy_set_at TIMESTAMP;

ALTER TABLE clinical.imaging_orders
    ADD COLUMN IF NOT EXISTS catalogue_item_id UUID;

ALTER TABLE clinical.imaging_orders
    ADD COLUMN IF NOT EXISTS catalogue_code VARCHAR(40);

CREATE INDEX IF NOT EXISTS idx_imaging_order_catalogue_item
    ON clinical.imaging_orders (catalogue_item_id);
