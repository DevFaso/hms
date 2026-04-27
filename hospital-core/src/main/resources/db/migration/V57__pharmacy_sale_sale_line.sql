-- P-07: Pharmacy OTC walk-in sale (sale header + per-medication line items).
-- Distinct from clinical.dispenses (prescription-bound) and billing.pharmacy_payments
-- (per-dispense payment record); captures cash transactions with no prescription.
--
-- Rollback:
--   DROP TABLE IF EXISTS clinical.sale_lines;
--   DROP TABLE IF EXISTS clinical.pharmacy_sales;

-- ── pharmacy_sales (header) ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS clinical.pharmacy_sales (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pharmacy_id UUID NOT NULL REFERENCES clinical.pharmacies(id),
    hospital_id UUID NOT NULL REFERENCES hospital.hospitals(id),
    -- Patient is intentionally nullable: anonymous walk-ins are common for OTC.
    patient_id UUID REFERENCES clinical.patients(id),
    sold_by UUID NOT NULL REFERENCES security.users(id),
    sale_date TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    payment_method VARCHAR(30) NOT NULL,
    total_amount NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'XOF',
    reference_number VARCHAR(120),
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    notes VARCHAR(1000),
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_psale_pharmacy ON clinical.pharmacy_sales(pharmacy_id);
CREATE INDEX IF NOT EXISTS idx_psale_hospital ON clinical.pharmacy_sales(hospital_id);
CREATE INDEX IF NOT EXISTS idx_psale_patient ON clinical.pharmacy_sales(patient_id);
CREATE INDEX IF NOT EXISTS idx_psale_sold_by ON clinical.pharmacy_sales(sold_by);
CREATE INDEX IF NOT EXISTS idx_psale_status ON clinical.pharmacy_sales(status);
CREATE INDEX IF NOT EXISTS idx_psale_sale_date ON clinical.pharmacy_sales(sale_date);

-- ── sale_lines (line items) ──────────────────────────────────────────────
-- Unit price captured per line so subsequent catalog price changes do not
-- retroactively alter the recorded sale.
CREATE TABLE IF NOT EXISTS clinical.sale_lines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sale_id UUID NOT NULL REFERENCES clinical.pharmacy_sales(id) ON DELETE CASCADE,
    medication_catalog_item_id UUID NOT NULL REFERENCES clinical.medication_catalog_items(id),
    stock_lot_id UUID REFERENCES clinical.stock_lots(id),
    quantity NUMERIC(12, 3) NOT NULL,
    unit_price NUMERIC(12, 2) NOT NULL,
    line_total NUMERIC(12, 2) NOT NULL,
    notes VARCHAR(500),
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sline_sale ON clinical.sale_lines(sale_id);
CREATE INDEX IF NOT EXISTS idx_sline_catalog_item ON clinical.sale_lines(medication_catalog_item_id);
CREATE INDEX IF NOT EXISTS idx_sline_stock_lot ON clinical.sale_lines(stock_lot_id);
