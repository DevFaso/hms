-- D3943 review: counterpart identity is server-owned, distinct from user-supplied references.
-- Legacy generic references are intentionally not trusted as evidence of a paired transfer.
ALTER TABLE clinical.stock_transactions ADD COLUMN transfer_origin_id uuid;
ALTER TABLE clinical.stock_transactions ADD CONSTRAINT fk_stock_transfer_origin
    FOREIGN KEY (transfer_origin_id) REFERENCES clinical.stock_transactions(id);
ALTER TABLE clinical.stock_transactions ADD CONSTRAINT uq_stock_transfer_origin UNIQUE (transfer_origin_id);
ALTER TABLE clinical.stock_transactions ADD CONSTRAINT ck_stock_transfer_receipt
    CHECK (transfer_origin_id IS NULL OR transaction_type = 'RECEIPT');
