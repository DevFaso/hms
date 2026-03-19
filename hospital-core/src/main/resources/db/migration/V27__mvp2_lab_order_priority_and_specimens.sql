-- =============================================================================
-- V27: MVP2 - Lab Order Priority & Specimen Tracking
-- Adds `priority` column to lab_orders and creates the lab_specimens table.
-- =============================================================================

-- ── Lab Order Priority ────────────────────────────────────────────────────────
ALTER TABLE lab.lab_orders
    ADD COLUMN IF NOT EXISTS priority VARCHAR(20) NOT NULL DEFAULT 'ROUTINE';

CREATE INDEX IF NOT EXISTS idx_lab_orders_priority ON lab.lab_orders(priority);

-- ── Lab Specimens ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS lab.lab_specimens (
    id               UUID          NOT NULL DEFAULT gen_random_uuid(),
    lab_order_id     UUID          NOT NULL,
    accession_number VARCHAR(50)   NOT NULL,
    barcode_value    VARCHAR(100),
    specimen_type    VARCHAR(50),
    collected_at     TIMESTAMP,
    collected_by_id  UUID,
    received_at      TIMESTAMP,
    received_by_id   UUID,
    current_location VARCHAR(100),
    status           VARCHAR(30)   NOT NULL DEFAULT 'PENDING',
    notes            VARCHAR(2048),
    created_at       TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_lab_specimens               PRIMARY KEY (id),
    CONSTRAINT uq_lab_specimens_accession     UNIQUE (accession_number),
    CONSTRAINT fk_lab_specimens_order         FOREIGN KEY (lab_order_id)
                                              REFERENCES lab.lab_orders(id)
);

CREATE INDEX IF NOT EXISTS idx_lab_specimens_lab_order  ON lab.lab_specimens(lab_order_id);
CREATE INDEX IF NOT EXISTS idx_lab_specimens_accession  ON lab.lab_specimens(accession_number);
CREATE INDEX IF NOT EXISTS idx_lab_specimens_status     ON lab.lab_specimens(status);
