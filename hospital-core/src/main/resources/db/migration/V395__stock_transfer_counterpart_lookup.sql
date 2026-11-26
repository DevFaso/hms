-- D3943: paired transfer receipts and return bounds are read by their source movement.
CREATE INDEX IF NOT EXISTS idx_stock_transactions_reference_type
    ON clinical.stock_transactions (reference_id, transaction_type)
    WHERE reference_id IS NOT NULL;
