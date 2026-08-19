-- V46: Pharmacy Registry - Hospital dispensaries and partner pharmacies

CREATE TABLE IF NOT EXISTS hospital.pharmacies (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(255) NOT NULL,
    license_number      VARCHAR(100),
    phone               VARCHAR(50),
    email               VARCHAR(255),
    address_line1       VARCHAR(500),
    address_line2       VARCHAR(500),
    city                VARCHAR(255),
    region              VARCHAR(255),
    country             VARCHAR(100) DEFAULT 'Burkina Faso',
    latitude            DOUBLE PRECISION,
    longitude           DOUBLE PRECISION,
    fulfillment_mode    VARCHAR(50) NOT NULL,
    tier                INTEGER NOT NULL DEFAULT 1,
    hospital_id         UUID NOT NULL REFERENCES hospital.hospitals(id),
    partner_agreement   BOOLEAN NOT NULL DEFAULT FALSE,
    partner_contact     VARCHAR(255),
    exchange_method     VARCHAR(50) DEFAULT 'SMS',
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    notes               TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_pharmacy_hospital ON hospital.pharmacies (hospital_id);
CREATE INDEX IF NOT EXISTS idx_pharmacy_active ON hospital.pharmacies (active);
CREATE INDEX IF NOT EXISTS idx_pharmacy_tier ON hospital.pharmacies (tier);
CREATE INDEX IF NOT EXISTS idx_pharmacy_city ON hospital.pharmacies (city);
