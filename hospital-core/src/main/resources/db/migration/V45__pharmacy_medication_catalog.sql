-- V45: Medication Catalog — Add columns missing from V43 baseline
-- Aligns schema with MedicationCatalogItem JPA entity

-- Widen existing columns to match entity @Column(length = 500)
ALTER TABLE clinical.medication_catalog_items ALTER COLUMN name_fr     TYPE VARCHAR(500);
ALTER TABLE clinical.medication_catalog_items ALTER COLUMN generic_name TYPE VARCHAR(500);

-- New columns
ALTER TABLE clinical.medication_catalog_items ADD COLUMN IF NOT EXISTS brand_name     VARCHAR(500);
ALTER TABLE clinical.medication_catalog_items ADD COLUMN IF NOT EXISTS strength_unit  VARCHAR(50);
ALTER TABLE clinical.medication_catalog_items ADD COLUMN IF NOT EXISTS route          VARCHAR(100);
ALTER TABLE clinical.medication_catalog_items ADD COLUMN IF NOT EXISTS category       VARCHAR(100);
ALTER TABLE clinical.medication_catalog_items ADD COLUMN IF NOT EXISTS essential_list BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE clinical.medication_catalog_items ADD COLUMN IF NOT EXISTS controlled     BOOLEAN NOT NULL DEFAULT FALSE;

-- Additional indexes
CREATE INDEX IF NOT EXISTS idx_med_catalog_generic ON clinical.medication_catalog_items (generic_name);
