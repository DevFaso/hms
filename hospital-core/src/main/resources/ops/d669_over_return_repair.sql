-- QA D669/D676: one-off repair of ONE reported fill, only after a physical
-- count confirms the excess. Run by an operator; :dispense_id, :operator and
-- :witness are replaced with quoted UUIDs. It books a visible, witnessed
-- ADJUSTMENT of minus the EXCESS only (returned - dispensed), referencing the
-- dispense, and takes the same excess off the lot and the item. Running it a
-- second time finds no excess and changes nothing.
BEGIN;
CREATE TEMP TABLE d669_excess ON COMMIT DROP AS
SELECT d.id AS dispense_id, d.stock_lot_id, t.inventory_item_id,
       SUM(t.quantity) - d.quantity_dispensed AS excess
FROM clinical.dispenses d
JOIN clinical.stock_transactions t
  ON t.reference_id = d.id AND t.transaction_type IN ('RETURN', 'ADJUSTMENT')
WHERE d.id = :dispense_id
GROUP BY d.id, d.stock_lot_id, t.inventory_item_id, d.quantity_dispensed
HAVING SUM(t.quantity) > d.quantity_dispensed;
INSERT INTO clinical.stock_transactions (id, inventory_item_id, stock_lot_id, transaction_type, quantity,
                                         reason, reference_id, performed_by, witnessed_by, created_at, updated_at)
SELECT gen_random_uuid(), e.inventory_item_id, e.stock_lot_id, 'ADJUSTMENT', -e.excess,
       'D669: over-return before the D604/D668 fix, confirmed by count', e.dispense_id,
       :operator, :witness, now(), now()
FROM d669_excess e;
UPDATE clinical.stock_lots l SET remaining_quantity = l.remaining_quantity - e.excess
FROM d669_excess e WHERE l.id = e.stock_lot_id AND l.remaining_quantity >= e.excess;
UPDATE clinical.inventory_items i SET quantity_on_hand = i.quantity_on_hand - e.excess
FROM d669_excess e WHERE i.id = e.inventory_item_id AND i.quantity_on_hand >= e.excess;
-- each statement above must report 1 row (the lot UPDATE 0 for an unlotted item), else ROLLBACK
COMMIT;
