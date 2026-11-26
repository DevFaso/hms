-- A stock count must notice an intervening stock/unit change even when its value returns to the original one.
ALTER TABLE clinical.inventory_items ADD COLUMN IF NOT EXISTS stock_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE clinical.stock_lots ADD COLUMN IF NOT EXISTS stock_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE clinical.pharmacy_stock_counts ADD COLUMN IF NOT EXISTS book_item_revision BIGINT;
ALTER TABLE clinical.pharmacy_stock_counts ADD COLUMN IF NOT EXISTS book_lot_revision BIGINT;

CREATE OR REPLACE FUNCTION clinical.track_inventory_stock_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.quantity_on_hand, NEW.unit, NEW.units_per_pack, NEW.pack_unit, NEW.medication_catalog_id, NEW.pharmacy_id)
       IS DISTINCT FROM ROW(OLD.quantity_on_hand, OLD.unit, OLD.units_per_pack, OLD.pack_unit, OLD.medication_catalog_id, OLD.pharmacy_id) THEN
        NEW.stock_revision := OLD.stock_revision + 1;
    ELSE
        NEW.stock_revision := OLD.stock_revision;
    END IF;
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS inventory_stock_revision ON clinical.inventory_items;
CREATE TRIGGER inventory_stock_revision BEFORE UPDATE ON clinical.inventory_items
    FOR EACH ROW EXECUTE FUNCTION clinical.track_inventory_stock_revision();

CREATE OR REPLACE FUNCTION clinical.track_lot_stock_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.remaining_quantity, NEW.initial_quantity, NEW.inventory_item_id, NEW.lot_number, NEW.expiry_date)
       IS DISTINCT FROM ROW(OLD.remaining_quantity, OLD.initial_quantity, OLD.inventory_item_id, OLD.lot_number, OLD.expiry_date) THEN
        NEW.stock_revision := OLD.stock_revision + 1;
    ELSE
        NEW.stock_revision := OLD.stock_revision;
    END IF;
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS lot_stock_revision ON clinical.stock_lots;
CREATE TRIGGER lot_stock_revision BEFORE UPDATE ON clinical.stock_lots
    FOR EACH ROW EXECUTE FUNCTION clinical.track_lot_stock_revision();
