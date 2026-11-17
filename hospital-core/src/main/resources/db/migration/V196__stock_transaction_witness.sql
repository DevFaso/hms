-- QA D157: a manual adjustment of a controlled substance records the second
-- person who witnessed it. Nullable: only controlled-substance adjustments
-- carry one, and every existing ledger row predates the rule.
ALTER TABLE clinical.stock_transactions
    ADD COLUMN IF NOT EXISTS witnessed_by UUID;

ALTER TABLE clinical.stock_transactions
    ADD CONSTRAINT fk_st_witness FOREIGN KEY (witnessed_by) REFERENCES security.users (id);
