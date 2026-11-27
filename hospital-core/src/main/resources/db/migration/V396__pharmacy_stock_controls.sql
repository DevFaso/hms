-- D3950: a manual retry and the scheduled sweep cannot notify the same colleague twice in one day.
CREATE TABLE clinical.low_stock_alert_receipts (
    inventory_item_id uuid NOT NULL REFERENCES clinical.inventory_items(id) ON DELETE CASCADE,
    recipient_user_id uuid NOT NULL REFERENCES security.users(id) ON DELETE CASCADE,
    alert_day date NOT NULL,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (inventory_item_id, recipient_user_id, alert_day)
);
