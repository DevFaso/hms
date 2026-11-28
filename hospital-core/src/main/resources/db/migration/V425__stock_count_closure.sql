-- QA r81 D4191: a physical count that will never be applied (the stock moved since, the count was a mistake, a
-- recount replaced it) is closed by a named person with a reason, instead of waiting in the list for ever.
ALTER TABLE clinical.pharmacy_stock_counts ADD COLUMN IF NOT EXISTS closed_at timestamp;
ALTER TABLE clinical.pharmacy_stock_counts ADD COLUMN IF NOT EXISTS closed_by_user_id uuid REFERENCES security.users(id);
ALTER TABLE clinical.pharmacy_stock_counts ADD COLUMN IF NOT EXISTS closed_by_name varchar(201);
ALTER TABLE clinical.pharmacy_stock_counts ADD COLUMN IF NOT EXISTS close_reason varchar(300);
ALTER TABLE clinical.pharmacy_stock_counts DROP CONSTRAINT IF EXISTS ck_stock_count_closure;
ALTER TABLE clinical.pharmacy_stock_counts ADD CONSTRAINT ck_stock_count_closure CHECK (
    (closed_at IS NULL AND closed_by_user_id IS NULL AND close_reason IS NULL)
    OR (closed_at IS NOT NULL AND closed_by_user_id IS NOT NULL AND applied_at IS NULL
        AND length(trim(coalesce(close_reason, ''))) > 0));
CREATE INDEX IF NOT EXISTS ix_stock_count_open
    ON clinical.pharmacy_stock_counts (hospital_id, inventory_item_id, stock_lot_id)
    WHERE applied_at IS NULL AND closed_at IS NULL;
