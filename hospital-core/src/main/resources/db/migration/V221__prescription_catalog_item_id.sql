-- V221 (QA D654): a prescription keeps the catalog item it was picked from, so
-- its controlled flag is read from that item even when the item has no code.
-- Plain statements, no DO block.

ALTER TABLE clinical.prescriptions
    ADD COLUMN IF NOT EXISTS medication_catalog_item_id UUID;
CREATE INDEX IF NOT EXISTS idx_prescriptions_catalog_item
    ON clinical.prescriptions (medication_catalog_item_id);
