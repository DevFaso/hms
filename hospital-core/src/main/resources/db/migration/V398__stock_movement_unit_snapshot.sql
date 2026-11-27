-- Future consumption rows retain the stock unit at booking time. Do not infer units for historical movements.
ALTER TABLE clinical.stock_transactions ADD COLUMN unit_snapshot varchar(60);
