CREATE TABLE clinical.pharmacy_stock_counts (
    id uuid PRIMARY KEY,
    hospital_id uuid NOT NULL REFERENCES hospital.hospitals(id),
    inventory_item_id uuid NOT NULL REFERENCES clinical.inventory_items(id),
    stock_lot_id uuid REFERENCES clinical.stock_lots(id),
    client_request_id uuid NOT NULL,
    book_quantity numeric(12,2) NOT NULL,
    counted_quantity numeric(12,2) NOT NULL CHECK (counted_quantity >= 0),
    variance numeric(12,2) NOT NULL,
    unit varchar(60),
    reason varchar(300) NOT NULL,
    medication_name_fr varchar(255),
    medication_name_en varchar(255),
    lot_number varchar(120),
    recorded_by_user_id uuid NOT NULL REFERENCES security.users(id),
    recorded_by_name varchar(201),
    recorded_at timestamp NOT NULL,
    applied_at timestamp,
    applied_by_user_id uuid REFERENCES security.users(id),
    applied_by_name varchar(201),
    adjustment_id uuid UNIQUE REFERENCES clinical.stock_transactions(id),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_stock_count_request UNIQUE (hospital_id, client_request_id),
    CONSTRAINT ck_stock_count_variance CHECK (variance = counted_quantity - book_quantity),
    CONSTRAINT ck_stock_count_confirmation CHECK (
        (applied_at IS NULL AND applied_by_user_id IS NULL AND adjustment_id IS NULL)
        OR (applied_at IS NOT NULL AND applied_by_user_id IS NOT NULL))
);
CREATE INDEX ix_stock_count_history ON clinical.pharmacy_stock_counts(hospital_id, inventory_item_id, recorded_at DESC);
