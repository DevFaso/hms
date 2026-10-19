-- QA D312 (round 5): duplicate inventory items created before the D208 guard
-- (the same drug - molecule, strength, unit, form - under two catalogue rows
-- of one pharmacy) are still listed and split every picker.
--
-- Reviewed, conservative clean-up: per pharmacy and drug, the item that holds
-- stock (or else the oldest) is kept. Any OTHER active twin is deactivated
-- only when it holds nothing: zero on hand and no lot with stock left. Its
-- lots, ledger rows and dispenses keep pointing at it, so history is intact,
-- and nothing is deleted. A twin that still holds stock is left active: moving
-- stock between items needs a ledger transfer a person makes, never a silent
-- update. The key folds case and spaces like the service (not accents: a
-- twin that differs only by accents stays, which is the safe side).
-- Plain statement, no DO block.
WITH keyed AS (
    SELECT i.id,
           i.pharmacy_id,
           i.created_at,
           i.quantity_on_hand,
           lower(regexp_replace(trim(COALESCE(NULLIF(trim(m.generic_name), ''), m.name_fr)), '\s+', ' ', 'g'))
               || '|' || lower(regexp_replace(trim(COALESCE(m.strength, '')), '\s+', ' ', 'g'))
               || '|' || lower(regexp_replace(trim(COALESCE(m.strength_unit, '')), '\s+', ' ', 'g'))
               || '|' || lower(regexp_replace(trim(COALESCE(m.form, '')), '\s+', ' ', 'g')) AS drug_key,
           EXISTS (SELECT 1 FROM clinical.stock_lots l
                    WHERE l.inventory_item_id = i.id AND l.remaining_quantity > 0) AS has_lot_stock
      FROM clinical.inventory_items i
      JOIN clinical.medication_catalog_items m ON m.id = i.medication_catalog_id
     WHERE i.active = true
), ranked AS (
    SELECT k.*,
           row_number() OVER (
               PARTITION BY k.pharmacy_id, k.drug_key
               ORDER BY (k.quantity_on_hand > 0 OR k.has_lot_stock) DESC, k.created_at, k.id) AS rn
      FROM keyed k
     WHERE k.drug_key NOT LIKE '|%'
)
UPDATE clinical.inventory_items i
   SET active = false,
       updated_at = now()
  FROM ranked r
 WHERE i.id = r.id
   AND r.rn > 1
   AND r.quantity_on_hand = 0
   AND r.has_lot_stock = false;
