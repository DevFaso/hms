-- =====================================================================
-- V420 — QA r81 D4181: an inventory item is either sold at a price or
-- documented as handed over at no charge.
--
-- 1. clinical.inventory_items.free_of_charge_reason: why the item costs
--    the patient nothing at this pharmacy (a free national programme, a
--    donation). Null on every item that is sold. A fill of an item with
--    neither a selling price nor this reason is refused by the
--    application.
-- 2. Items a stock transfer created at its destination took no selling
--    price. Each such item that still has no price at all (NULL) takes
--    the price its source item has now, and the change is kept in the
--    price history under the person who recorded the transfer. Left as
--    it is: an item that has a price, a price of 0 included (0 may have
--    been set on purpose), an item whose latest price-history change set
--    0, an item documented as free, and an item whose source has no
--    price.
--
-- Additive and idempotent: IF NOT EXISTS on the column, and the backfill
-- only touches rows whose price is NULL: a second run changes nothing.
-- Rollback: none declared (forward-only).
-- =====================================================================

ALTER TABLE clinical.inventory_items
    ADD COLUMN IF NOT EXISTS free_of_charge_reason VARCHAR(500);

WITH priced AS (
    SELECT DISTINCT ON (dest.id)
           dest.id              AS item_id,
           p.hospital_id        AS hospital_id,
           dest.pharmacy_id     AS pharmacy_id,
           src.unit_price       AS price,
           receipt.performed_by AS changed_by
    FROM clinical.inventory_items dest
    JOIN clinical.pharmacies p ON p.id = dest.pharmacy_id
    JOIN clinical.stock_transactions receipt
         ON receipt.inventory_item_id = dest.id AND receipt.transfer_origin_id IS NOT NULL
    JOIN clinical.stock_transactions origin ON origin.id = receipt.transfer_origin_id
    JOIN clinical.inventory_items src ON src.id = origin.inventory_item_id
    WHERE dest.unit_price IS NULL
      AND dest.free_of_charge_reason IS NULL
      AND NOT EXISTS (
          SELECT 1
          FROM clinical.pharmacy_price_changes latest
          WHERE latest.inventory_item_id = dest.id
            AND latest.new_price = 0
            AND NOT EXISTS (
                SELECT 1
                FROM clinical.pharmacy_price_changes later
                WHERE later.inventory_item_id = dest.id
                  AND (later.changed_at, later.created_at) > (latest.changed_at, latest.created_at)))
      AND src.unit_price > 0
      AND src.unit IS NOT DISTINCT FROM dest.unit
      AND src.units_per_pack IS NOT DISTINCT FROM dest.units_per_pack
    ORDER BY dest.id, receipt.created_at DESC
), history AS (
    INSERT INTO clinical.pharmacy_price_changes
        (id, hospital_id, pharmacy_id, inventory_item_id, old_price, new_price, reason, changed_by, changed_at,
         created_at, updated_at)
    SELECT gen_random_uuid(), priced.hospital_id, priced.pharmacy_id, priced.item_id, NULL, priced.price,
           'Prix repris de la pharmacie d''origine du transfert (V420)', priced.changed_by, now(), now(), now()
    FROM priced
    RETURNING inventory_item_id
)
UPDATE clinical.inventory_items dest
SET unit_price = priced.price, updated_at = now()
FROM priced
WHERE dest.id = priced.item_id;
