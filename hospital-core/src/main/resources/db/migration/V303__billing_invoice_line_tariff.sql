-- V303 (billing-money go-live plan PR 3, scorecard M1): a checkout line is
-- priced from the hospital's price list (V302) and remembers where its price
-- came from.
--
-- billing.invoice_items gains:
--   price_list_item_id   the tariff item that priced the line (NULL when none)
--   price_list_price_id  the price version in force on the service date, whose
--                        unit price the line copied (D-4: a later price never
--                        rewrites the line; it keeps this version's price)
--   price_source         TARIFF        priced from the price list
--                        SERVICE_NAME  priced by a clinical.treatments row whose
--                                      name matched (the pre-tariff rule)
--                        MANUAL        priced by a person at the till
--                        UNPRICED      posted at 0 for the cashier to price
--                        LEGACY        written before V303
--   tariff_unit_price    the tariff's price when a cashier overrode it on a
--                        draft line (D-2); NULL otherwise
--   override_reason      the cashier's required reason for that override
--
-- The (price_list_price_id, price_list_item_id) pair references the version of
-- that very item, so a line can never name one item and another item's price.
--
-- Backfill: lines already linked to a service (related_service_id) are
-- SERVICE_NAME; every other existing line stays LEGACY. Nothing else is
-- rewritten, and no amount changes.
--
-- Same PR, deferred from the #1179 review: a PROCEDURE_CODE link key is stored
-- trimmed, with single spaces, in upper case (the charge source looks it up
-- the same way). Existing keys are normalised here, except where two ACTIVE
-- items of one hospital would then collide on the one-active-item-per-link
-- index; those are left as typed for the hospital to resolve.
--
-- Idempotent (IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before each ADD). Plain
-- DDL, no DO block. Rollback: none declared (forward-only, additive columns).
-- V301-V319 are reserved for the billing-money plan.

ALTER TABLE billing.invoice_items ADD COLUMN IF NOT EXISTS price_list_item_id  UUID;
ALTER TABLE billing.invoice_items ADD COLUMN IF NOT EXISTS price_list_price_id UUID;
ALTER TABLE billing.invoice_items ADD COLUMN IF NOT EXISTS price_source        VARCHAR(20) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE billing.invoice_items ADD COLUMN IF NOT EXISTS tariff_unit_price   NUMERIC(12, 2);
ALTER TABLE billing.invoice_items ADD COLUMN IF NOT EXISTS override_reason     VARCHAR(500);

-- Target of the line's composite FK: a version always belongs to one item.
CREATE UNIQUE INDEX IF NOT EXISTS uq_price_list_price_id_item
    ON billing.price_list_prices (id, item_id);

ALTER TABLE billing.invoice_items DROP CONSTRAINT IF EXISTS fk_item_price_list_item;
ALTER TABLE billing.invoice_items ADD CONSTRAINT fk_item_price_list_item
    FOREIGN KEY (price_list_item_id) REFERENCES billing.price_list_items (id);

ALTER TABLE billing.invoice_items DROP CONSTRAINT IF EXISTS fk_item_price_list_price;
ALTER TABLE billing.invoice_items ADD CONSTRAINT fk_item_price_list_price
    FOREIGN KEY (price_list_price_id, price_list_item_id) REFERENCES billing.price_list_prices (id, item_id);

ALTER TABLE billing.invoice_items DROP CONSTRAINT IF EXISTS ck_item_price_source;
ALTER TABLE billing.invoice_items ADD CONSTRAINT ck_item_price_source
    CHECK (price_source IN ('TARIFF', 'SERVICE_NAME', 'MANUAL', 'UNPRICED', 'LEGACY'));

-- A version is named together with its item, or not at all.
ALTER TABLE billing.invoice_items DROP CONSTRAINT IF EXISTS ck_item_price_list_pair;
ALTER TABLE billing.invoice_items ADD CONSTRAINT ck_item_price_list_pair
    CHECK ((price_list_item_id IS NULL) = (price_list_price_id IS NULL));

-- A TARIFF line always names the version it copied.
ALTER TABLE billing.invoice_items DROP CONSTRAINT IF EXISTS ck_item_tariff_version;
ALTER TABLE billing.invoice_items ADD CONSTRAINT ck_item_tariff_version
    CHECK (price_source <> 'TARIFF' OR price_list_price_id IS NOT NULL);

-- An overridden tariff keeps the tariff's whole-franc price, the version and the reason.
ALTER TABLE billing.invoice_items DROP CONSTRAINT IF EXISTS ck_item_tariff_override;
ALTER TABLE billing.invoice_items ADD CONSTRAINT ck_item_tariff_override
    CHECK (tariff_unit_price IS NULL
        OR (tariff_unit_price >= 0 AND tariff_unit_price = trunc(tariff_unit_price)
            AND price_list_price_id IS NOT NULL
            AND override_reason IS NOT NULL AND length(btrim(override_reason)) > 0));

CREATE INDEX IF NOT EXISTS idx_item_price_list_price
    ON billing.invoice_items (price_list_price_id)
    WHERE price_list_price_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_item_price_list_item
    ON billing.invoice_items (price_list_item_id)
    WHERE price_list_item_id IS NOT NULL;

-- Backfill: a line already linked to a service was priced by its name.
UPDATE billing.invoice_items
   SET price_source = 'SERVICE_NAME'
 WHERE price_source = 'LEGACY'
   AND related_service_id IS NOT NULL;

-- PROCEDURE_CODE link keys: trimmed, single spaces, upper case (#1179 review).
UPDATE billing.price_list_items i
   SET link_key = upper(regexp_replace(btrim(i.link_key), '\s+', ' ', 'g'))
 WHERE i.link_type = 'PROCEDURE_CODE'
   AND i.link_key <> upper(regexp_replace(btrim(i.link_key), '\s+', ' ', 'g'))
   AND NOT (i.active AND EXISTS (
        SELECT 1
          FROM billing.price_list_items o
         WHERE o.hospital_id = i.hospital_id
           AND o.id <> i.id
           AND o.active
           AND o.link_type = 'PROCEDURE_CODE'
           AND upper(regexp_replace(btrim(o.link_key), '\s+', ' ', 'g'))
             = upper(regexp_replace(btrim(i.link_key), '\s+', ' ', 'g'))));
