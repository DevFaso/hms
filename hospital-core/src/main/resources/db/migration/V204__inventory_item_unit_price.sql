-- QA D264 (D204): a pharmacy payment was checked only for being positive, so
-- 999 999 XOF was accepted on a 9-tablet dispense: nothing in the schema said
-- what a dispense costs. The selling price per unit now lives on the
-- pharmacy's inventory item; a dispense costs quantity_dispensed x unit_price
-- and a payment must match it. Nullable, no default: an item with no price
-- cannot be paid for at the checkout until the pharmacy sets one.
ALTER TABLE clinical.inventory_items
    ADD COLUMN IF NOT EXISTS unit_price NUMERIC(12, 2);
