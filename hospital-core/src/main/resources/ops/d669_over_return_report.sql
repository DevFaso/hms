-- QA D669/D676: operator report, read-only. Lists every fill whose returns
-- (ALL of them, the cancel's own automatic RETURN included) exceed what it
-- delivered, with the excess only (returned - dispensed). A normal cancel
-- returns exactly what was dispensed, so it is not listed. A D669 repair
-- ADJUSTMENT already booked against the fill (reference_id = the dispense)
-- counts against the excess, so a repaired fill drops out.
WITH moved AS (
    SELECT d.id AS dispense_id, d.status, d.stock_lot_id, t.inventory_item_id,
           d.quantity_dispensed,
           COALESCE(SUM(CASE WHEN t.transaction_type = 'RETURN' THEN t.quantity END), 0) AS returned,
           COALESCE(SUM(CASE WHEN t.transaction_type = 'ADJUSTMENT' THEN t.quantity END), 0) AS repaired
    FROM clinical.dispenses d
    JOIN clinical.stock_transactions t
      ON t.reference_id = d.id AND t.transaction_type IN ('RETURN', 'ADJUSTMENT')
    GROUP BY d.id, d.status, d.stock_lot_id, t.inventory_item_id, d.quantity_dispensed
)
SELECT dispense_id, status, stock_lot_id, inventory_item_id, quantity_dispensed, returned,
       returned + repaired - quantity_dispensed AS excess
FROM moved
WHERE returned + repaired > quantity_dispensed
ORDER BY stock_lot_id, dispense_id;
