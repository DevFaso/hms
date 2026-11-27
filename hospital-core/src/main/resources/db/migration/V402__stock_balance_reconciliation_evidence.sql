CREATE TABLE clinical.stock_balance_reconciliation_lot_counts (
    movement_id uuid NOT NULL REFERENCES clinical.stock_transactions(id),
    stock_lot_id uuid NOT NULL REFERENCES clinical.stock_lots(id),
    counted_quantity numeric(12,2) NOT NULL CHECK (counted_quantity >= 0),
    PRIMARY KEY (movement_id, stock_lot_id)
);
