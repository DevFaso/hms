ALTER TABLE clinical.stock_transactions ALTER COLUMN supplier TYPE varchar(255);
ALTER TABLE clinical.stock_transactions ALTER COLUMN unit_cost TYPE numeric(14,4);
ALTER TABLE clinical.stock_transactions ADD COLUMN receipt_date date;
ALTER TABLE clinical.stock_transactions ADD COLUMN receipt_notes varchar(1000);
