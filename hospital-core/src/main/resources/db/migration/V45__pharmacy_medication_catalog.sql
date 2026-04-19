-- V45: Medication Catalog - Normalized medication reference table
-- Linked to Burkina essential-medicines list + optional RxNorm crosswalk

CREATE TABLE IF NOT EXISTS clinical.medication_catalog_items (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name_fr         VARCHAR(500) NOT NULL,
    generic_name    VARCHAR(500) NOT NULL,
    brand_name      VARCHAR(500),
    atc_code        VARCHAR(20),
    form            VARCHAR(100),
    strength        VARCHAR(100),
    strength_unit   VARCHAR(50),
    rxnorm_code     VARCHAR(20),
    route           VARCHAR(100),
    category        VARCHAR(100),
    essential_list  BOOLEAN NOT NULL DEFAULT FALSE,
    controlled      BOOLEAN NOT NULL DEFAULT FALSE,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    hospital_id     UUID NOT NULL REFERENCES hospital.hospitals(id),
    description     TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_med_catalog_name_fr ON clinical.medication_catalog_items (name_fr);
CREATE INDEX IF NOT EXISTS idx_med_catalog_generic ON clinical.medication_catalog_items (generic_name);
CREATE INDEX IF NOT EXISTS idx_med_catalog_atc ON clinical.medication_catalog_items (atc_code);
CREATE INDEX IF NOT EXISTS idx_med_catalog_hospital ON clinical.medication_catalog_items (hospital_id);
CREATE INDEX IF NOT EXISTS idx_med_catalog_active ON clinical.medication_catalog_items (active);
