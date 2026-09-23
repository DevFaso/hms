-- V161: lab order routed to a performing laboratory (audit gap B1).
--
-- A lab order carried exactly one hospital: the ORDERING hospital. Every read
-- and every lab-side write was scoped to it, so a laboratory that is a
-- different hospital in the platform could neither see nor result an order
-- sent to it. This column names the hospital that performs the test.
--
-- Semantics: NULL = the ordering hospital performs the test itself (the
-- historic behaviour), so nothing is backfilled. The performing hospital is
-- always a different hospital from the ordering one; the service normalises
-- "performed by ourselves" to NULL.
ALTER TABLE lab.lab_orders
    ADD COLUMN IF NOT EXISTS performing_hospital_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_laborder_performing_hospital'
    ) THEN
        ALTER TABLE lab.lab_orders
            ADD CONSTRAINT fk_laborder_performing_hospital
            FOREIGN KEY (performing_hospital_id)
            REFERENCES hospital.hospitals (id);
    END IF;
END $$;

-- The performing lab's incoming worklist: "orders sent to us".
CREATE INDEX IF NOT EXISTS idx_lab_order_performing_hospital
    ON lab.lab_orders (performing_hospital_id);
