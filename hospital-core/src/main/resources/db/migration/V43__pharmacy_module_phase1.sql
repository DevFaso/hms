-- =============================================================================
-- V43: Pharmacy Module — Phase 1 Foundation
-- Creates tables for: medication catalog, pharmacy registry, inventory/stock,
-- dispensing, pharmacy payments, and pharmacy claims.
-- Adds new pharmacy roles and local identifier columns.
-- =============================================================================

-- ─────────────────────────────────────────────────────────────────────────────
-- 0. Seed new pharmacy roles
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO "security".roles (id, code, name, description, created_at, updated_at) VALUES
    (gen_random_uuid(), 'ROLE_PHARMACY_VERIFIER', 'ROLE_PHARMACY_VERIFIER', 'Pharmacist authorized to verify and co-sign controlled-substance dispensing', NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_INVENTORY_CLERK',   'ROLE_INVENTORY_CLERK',   'Pharmacy staff managing goods receipt, stock adjustments, and inventory counts', NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_STORE_MANAGER',     'ROLE_STORE_MANAGER',     'Senior pharmacy staff overseeing inventory operations, reorder thresholds, and stock reports', NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_CLAIMS_REVIEWER',   'ROLE_CLAIMS_REVIEWER',   'Staff reviewing and submitting pharmacy insurance claims for AMU/insurers', NOW(), NOW())
ON CONFLICT (code) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. Add local identifier columns to existing pharmacy_fills table
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE clinical.pharmacy_fills
    ADD COLUMN IF NOT EXISTS pharmacy_license VARCHAR(50),
    ADD COLUMN IF NOT EXISTS facility_code    VARCHAR(50);

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. Medication Catalog (US-1.1)
--    Normalized medication reference linked to Burkina essential-medicines list.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS clinical.medication_catalog_items (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    hospital_id     UUID            NOT NULL,
    code            VARCHAR(30)     NOT NULL,
    name_fr         VARCHAR(255)    NOT NULL,
    generic_name    VARCHAR(255),
    atc_code        VARCHAR(10),
    form            VARCHAR(80),
    strength        VARCHAR(100),
    unit            VARCHAR(60),
    rxnorm_code     VARCHAR(20),
    description     VARCHAR(1000),
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_mci_hospital FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals(id),
    CONSTRAINT uq_mci_code_hospital UNIQUE (hospital_id, code)
);

CREATE INDEX IF NOT EXISTS idx_mci_hospital ON clinical.medication_catalog_items (hospital_id);
CREATE INDEX IF NOT EXISTS idx_mci_atc_code ON clinical.medication_catalog_items (atc_code);
CREATE INDEX IF NOT EXISTS idx_mci_name_fr  ON clinical.medication_catalog_items (name_fr);
CREATE INDEX IF NOT EXISTS idx_mci_active   ON clinical.medication_catalog_items (active);

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. Pharmacy Registry (US-1.2)
--    Registered pharmacies: hospital dispensaries & community partners.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS clinical.pharmacies (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    hospital_id         UUID            NOT NULL,
    name                VARCHAR(255)    NOT NULL,
    pharmacy_type       VARCHAR(30)     NOT NULL DEFAULT 'HOSPITAL_DISPENSARY',
    license_number      VARCHAR(50),
    facility_code       VARCHAR(50),
    phone_number        VARCHAR(30),
    email               VARCHAR(255),
    address_line1       VARCHAR(255),
    address_line2       VARCHAR(255),
    city                VARCHAR(100),
    region              VARCHAR(100),
    postal_code         VARCHAR(20),
    country             VARCHAR(60)     DEFAULT 'Burkina Faso',
    fulfillment_mode    VARCHAR(30)     NOT NULL DEFAULT 'OUTPATIENT_HOSPITAL',
    npi                 VARCHAR(50),
    ncpdp               VARCHAR(20),
    active              BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_pharmacy_hospital FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals(id)
);

CREATE INDEX IF NOT EXISTS idx_pharmacy_hospital  ON clinical.pharmacies (hospital_id);
CREATE INDEX IF NOT EXISTS idx_pharmacy_type      ON clinical.pharmacies (pharmacy_type);
CREATE INDEX IF NOT EXISTS idx_pharmacy_active     ON clinical.pharmacies (active);
CREATE INDEX IF NOT EXISTS idx_pharmacy_license    ON clinical.pharmacies (license_number);

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. Inventory Item (US-2.2)
--    Per-medication stock summary at a pharmacy.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS clinical.inventory_items (
    id                      UUID            NOT NULL DEFAULT gen_random_uuid(),
    pharmacy_id             UUID            NOT NULL,
    medication_catalog_id   UUID            NOT NULL,
    quantity_on_hand        NUMERIC(12, 2)  NOT NULL DEFAULT 0,
    reorder_threshold       NUMERIC(12, 2)  DEFAULT 0,
    reorder_quantity        NUMERIC(12, 2)  DEFAULT 0,
    unit                    VARCHAR(60),
    active                  BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    updated_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_inv_pharmacy  FOREIGN KEY (pharmacy_id) REFERENCES clinical.pharmacies(id),
    CONSTRAINT fk_inv_medication FOREIGN KEY (medication_catalog_id) REFERENCES clinical.medication_catalog_items(id),
    CONSTRAINT uq_inv_pharmacy_med UNIQUE (pharmacy_id, medication_catalog_id)
);

CREATE INDEX IF NOT EXISTS idx_inv_pharmacy   ON clinical.inventory_items (pharmacy_id);
CREATE INDEX IF NOT EXISTS idx_inv_medication ON clinical.inventory_items (medication_catalog_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- 5. Stock Lot (US-2.1)
--    Individual stock lots with lot number, expiry, and traceability.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS clinical.stock_lots (
    id                      UUID            NOT NULL DEFAULT gen_random_uuid(),
    inventory_item_id       UUID            NOT NULL,
    lot_number              VARCHAR(80)     NOT NULL,
    expiry_date             DATE            NOT NULL,
    initial_quantity        NUMERIC(12, 2)  NOT NULL,
    remaining_quantity      NUMERIC(12, 2)  NOT NULL,
    supplier                VARCHAR(255),
    unit_cost               NUMERIC(12, 4),
    received_date           DATE            NOT NULL,
    received_by             UUID,
    notes                   VARCHAR(1000),
    created_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    updated_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_sl_inventory FOREIGN KEY (inventory_item_id) REFERENCES clinical.inventory_items(id),
    CONSTRAINT fk_sl_received_by FOREIGN KEY (received_by) REFERENCES "security".users(id),
    CONSTRAINT chk_sl_remaining_nonneg CHECK (remaining_quantity >= 0)
);

CREATE INDEX IF NOT EXISTS idx_sl_inventory   ON clinical.stock_lots (inventory_item_id);
CREATE INDEX IF NOT EXISTS idx_sl_expiry      ON clinical.stock_lots (expiry_date);
CREATE INDEX IF NOT EXISTS idx_sl_lot_number  ON clinical.stock_lots (lot_number);

-- ─────────────────────────────────────────────────────────────────────────────
-- 6. Stock Transaction Ledger (US-2.3)
--    Immutable ledger of all stock movements.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS clinical.stock_transactions (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    inventory_item_id   UUID            NOT NULL,
    stock_lot_id        UUID,
    transaction_type    VARCHAR(30)     NOT NULL,
    quantity            NUMERIC(12, 2)  NOT NULL,
    reason              VARCHAR(500),
    reference_id        UUID,
    performed_by        UUID            NOT NULL,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_st_inventory FOREIGN KEY (inventory_item_id) REFERENCES clinical.inventory_items(id),
    CONSTRAINT fk_st_lot       FOREIGN KEY (stock_lot_id)      REFERENCES clinical.stock_lots(id),
    CONSTRAINT fk_st_user      FOREIGN KEY (performed_by)      REFERENCES "security".users(id)
);

CREATE INDEX IF NOT EXISTS idx_st_inventory ON clinical.stock_transactions (inventory_item_id);
CREATE INDEX IF NOT EXISTS idx_st_lot       ON clinical.stock_transactions (stock_lot_id);
CREATE INDEX IF NOT EXISTS idx_st_type      ON clinical.stock_transactions (transaction_type);
CREATE INDEX IF NOT EXISTS idx_st_date      ON clinical.stock_transactions (created_at);

-- ─────────────────────────────────────────────────────────────────────────────
-- 7. Dispense (US-3.2)
--    Records each dispensing event from a hospital dispensary.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS clinical.dispenses (
    id                      UUID            NOT NULL DEFAULT gen_random_uuid(),
    prescription_id         UUID            NOT NULL,
    patient_id              UUID            NOT NULL,
    pharmacy_id             UUID            NOT NULL,
    stock_lot_id            UUID,
    dispensed_by            UUID            NOT NULL,
    verified_by             UUID,
    medication_catalog_id   UUID,
    medication_name         VARCHAR(255)    NOT NULL,
    quantity_requested      NUMERIC(12, 2)  NOT NULL,
    quantity_dispensed       NUMERIC(12, 2)  NOT NULL,
    unit                    VARCHAR(60),
    substitution            BOOLEAN         NOT NULL DEFAULT FALSE,
    substitution_reason     VARCHAR(500),
    status                  VARCHAR(30)     NOT NULL DEFAULT 'COMPLETED',
    notes                   VARCHAR(1000),
    dispensed_at            TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    created_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    updated_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_disp_prescription FOREIGN KEY (prescription_id) REFERENCES clinical.prescriptions(id),
    CONSTRAINT fk_disp_patient      FOREIGN KEY (patient_id)      REFERENCES clinical.patients(id),
    CONSTRAINT fk_disp_pharmacy     FOREIGN KEY (pharmacy_id)     REFERENCES clinical.pharmacies(id),
    CONSTRAINT fk_disp_lot          FOREIGN KEY (stock_lot_id)    REFERENCES clinical.stock_lots(id),
    CONSTRAINT fk_disp_dispensed_by FOREIGN KEY (dispensed_by)    REFERENCES "security".users(id),
    CONSTRAINT fk_disp_verified_by  FOREIGN KEY (verified_by)     REFERENCES "security".users(id),
    CONSTRAINT fk_disp_medication   FOREIGN KEY (medication_catalog_id) REFERENCES clinical.medication_catalog_items(id),
    CONSTRAINT chk_disp_qty_positive CHECK (quantity_dispensed > 0)
);

CREATE INDEX IF NOT EXISTS idx_disp_prescription ON clinical.dispenses (prescription_id);
CREATE INDEX IF NOT EXISTS idx_disp_patient      ON clinical.dispenses (patient_id);
CREATE INDEX IF NOT EXISTS idx_disp_pharmacy     ON clinical.dispenses (pharmacy_id);
CREATE INDEX IF NOT EXISTS idx_disp_status       ON clinical.dispenses (status);
CREATE INDEX IF NOT EXISTS idx_disp_dispensed_at ON clinical.dispenses (dispensed_at);

-- ─────────────────────────────────────────────────────────────────────────────
-- 8. Pharmacy Payment (US-5.1)
--    Payment records for pharmacy checkout.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS billing.pharmacy_payments (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    dispense_id         UUID            NOT NULL,
    patient_id          UUID            NOT NULL,
    hospital_id         UUID            NOT NULL,
    payment_method      VARCHAR(30)     NOT NULL,
    amount              NUMERIC(12, 2)  NOT NULL,
    currency            VARCHAR(10)     NOT NULL DEFAULT 'XOF',
    reference_number    VARCHAR(120),
    received_by         UUID            NOT NULL,
    notes               VARCHAR(1000),
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_pp_dispense  FOREIGN KEY (dispense_id)  REFERENCES clinical.dispenses(id),
    CONSTRAINT fk_pp_patient   FOREIGN KEY (patient_id)   REFERENCES clinical.patients(id),
    CONSTRAINT fk_pp_hospital  FOREIGN KEY (hospital_id)  REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_pp_received  FOREIGN KEY (received_by)  REFERENCES "security".users(id),
    CONSTRAINT chk_pp_amount_positive CHECK (amount > 0)
);

CREATE INDEX IF NOT EXISTS idx_pp_dispense  ON billing.pharmacy_payments (dispense_id);
CREATE INDEX IF NOT EXISTS idx_pp_patient   ON billing.pharmacy_payments (patient_id);
CREATE INDEX IF NOT EXISTS idx_pp_hospital  ON billing.pharmacy_payments (hospital_id);
CREATE INDEX IF NOT EXISTS idx_pp_method    ON billing.pharmacy_payments (payment_method);

-- ─────────────────────────────────────────────────────────────────────────────
-- 9. Pharmacy Claim (US-6.1)
--    Insurance claims linked to dispense records.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS billing.pharmacy_claims (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    dispense_id         UUID            NOT NULL,
    patient_id          UUID            NOT NULL,
    hospital_id         UUID            NOT NULL,
    coverage_reference  VARCHAR(255),
    claim_status        VARCHAR(30)     NOT NULL DEFAULT 'DRAFT',
    amount              NUMERIC(12, 2)  NOT NULL,
    currency            VARCHAR(10)     NOT NULL DEFAULT 'XOF',
    submitted_at        TIMESTAMP WITHOUT TIME ZONE,
    submitted_by        UUID,
    rejection_reason    VARCHAR(1000),
    notes               VARCHAR(1000),
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_pc_dispense  FOREIGN KEY (dispense_id)   REFERENCES clinical.dispenses(id),
    CONSTRAINT fk_pc_patient   FOREIGN KEY (patient_id)    REFERENCES clinical.patients(id),
    CONSTRAINT fk_pc_hospital  FOREIGN KEY (hospital_id)   REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_pc_submitted FOREIGN KEY (submitted_by)  REFERENCES "security".users(id),
    CONSTRAINT chk_pc_amount_nonneg CHECK (amount >= 0)
);

CREATE INDEX IF NOT EXISTS idx_pc_dispense  ON billing.pharmacy_claims (dispense_id);
CREATE INDEX IF NOT EXISTS idx_pc_patient   ON billing.pharmacy_claims (patient_id);
CREATE INDEX IF NOT EXISTS idx_pc_hospital  ON billing.pharmacy_claims (hospital_id);
CREATE INDEX IF NOT EXISTS idx_pc_status    ON billing.pharmacy_claims (claim_status);
