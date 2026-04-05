-- =============================================================================
-- V35: Lab Instrument & Inventory Management (MVP 4)
--
-- Adds:
--   1. lab.lab_instruments  – track lab instruments, calibration, maintenance
--   2. lab.lab_inventory_items – track reagents, consumables, reorder alerts
--
-- Rollback:
--   DROP TABLE IF EXISTS lab.lab_inventory_items;
--   DROP TABLE IF EXISTS lab.lab_instruments;
-- =============================================================================

-- ── Lab Instruments ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS lab.lab_instruments (
    id                      UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    name                    VARCHAR(255)    NOT NULL,
    manufacturer            VARCHAR(255),
    model_number            VARCHAR(255),
    serial_number           VARCHAR(100)    NOT NULL,
    hospital_id             UUID            NOT NULL
        CONSTRAINT fk_lab_instrument_hospital
            REFERENCES hospital.hospitals(id) ON DELETE CASCADE,
    department_id           UUID
        CONSTRAINT fk_lab_instrument_department
            REFERENCES hospital.departments(id) ON DELETE SET NULL,
    status                  VARCHAR(30)     NOT NULL DEFAULT 'ACTIVE',
    installation_date       DATE,
    last_calibration_date   DATE,
    next_calibration_date   DATE,
    last_maintenance_date   DATE,
    next_maintenance_date   DATE,
    notes                   VARCHAR(2048),
    active                  BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP       NOT NULL DEFAULT NOW()
);

ALTER TABLE lab.lab_instruments
    ADD CONSTRAINT uq_lab_instrument_serial UNIQUE (hospital_id, serial_number);

CREATE INDEX idx_lab_instrument_hospital
    ON lab.lab_instruments(hospital_id);

CREATE INDEX idx_lab_instrument_department
    ON lab.lab_instruments(department_id);

CREATE INDEX idx_lab_instrument_status
    ON lab.lab_instruments(status);

-- ── Lab Inventory Items ───────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS lab.lab_inventory_items (
    id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(255)    NOT NULL,
    item_code           VARCHAR(100)    NOT NULL,
    category            VARCHAR(100),
    hospital_id         UUID            NOT NULL
        CONSTRAINT fk_lab_inventory_hospital
            REFERENCES hospital.hospitals(id) ON DELETE CASCADE,
    quantity            INTEGER         NOT NULL DEFAULT 0,
    unit                VARCHAR(50),
    reorder_threshold   INTEGER         NOT NULL DEFAULT 0,
    supplier            VARCHAR(255),
    lot_number          VARCHAR(100),
    expiration_date     DATE,
    notes               VARCHAR(2048),
    active              BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP       NOT NULL DEFAULT NOW()
);

ALTER TABLE lab.lab_inventory_items
    ADD CONSTRAINT uq_lab_inventory_code UNIQUE (hospital_id, item_code);

CREATE INDEX idx_lab_inventory_hospital
    ON lab.lab_inventory_items(hospital_id);

CREATE INDEX idx_lab_inventory_category
    ON lab.lab_inventory_items(category);

CREATE INDEX idx_lab_inventory_low_stock
    ON lab.lab_inventory_items(quantity, reorder_threshold);
