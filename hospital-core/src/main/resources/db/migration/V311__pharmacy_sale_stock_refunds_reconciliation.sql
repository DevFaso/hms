-- V311 (billing-money go-live plan PR 13, scorecard M4; QA r65 D2670-D2676):
-- pharmacy money and stock reconcile.
--
-- clinical.inventory_items.units_per_pack / pack_unit (D2674) - stock kept in
--   packs ("plaquette de 10 comprimés") is counted in the unit a prescription
--   is written in ("comprimé"). units_per_pack is how many of those units one
--   pack holds; pack_unit is the pack's own name. Both null on an item that
--   was never converted (its stock is counted as it always was).
--
-- clinical.dispenses.billing_deferred_* (D2675) - a fill handed over before it
--   is paid must be marked "à facturer" (a reason, by whom, when). Null on
--   every fill that was paid, covered or free.
--
-- clinical.pharmacy_refunds (D2676, PR 13) - money given back for a patient
--   return (a counter sale or a dispense). REQUESTED with a reason, APPROVED or
--   REJECTED by ANOTHER hospital admin or accountant (owner decision D-3, the
--   CHECK holds it here too), PAID when handed over (paid_at is the day it is
--   money out), or CANCELLED before payment. One live refund per return
--   movement. Whole francs, > 0. No card data is stored: reference and reasons
--   are refused by the service when they hold a card number.
--
-- clinical.inventory_items.pack_size_set_at (review of #1239) - when the pack
--   size was set. A sale, fill or return recorded before it is counted in
--   packs, one recorded after in units; a refund converts between the two.
--
-- clinical.pharmacy_price_changes (review of #1239) - the history of an item's
--   selling price: every change with the old and the new price, the reason,
--   who and when. The reconciliation compares a sale with the price in force
--   when it was made, not with today's. Append-only.
--
-- Stock movements of type SALE (D2670) need no DDL: transaction_type is a
-- VARCHAR(30) with no CHECK.
--
-- Idempotent (IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before each ADD). Plain
-- DDL, no DO block. Forward-only: additive columns and a new table.

ALTER TABLE clinical.inventory_items ADD COLUMN IF NOT EXISTS units_per_pack INTEGER;
ALTER TABLE clinical.inventory_items ADD COLUMN IF NOT EXISTS pack_unit VARCHAR(60);
ALTER TABLE clinical.inventory_items DROP CONSTRAINT IF EXISTS ck_inv_units_per_pack;
ALTER TABLE clinical.inventory_items ADD CONSTRAINT ck_inv_units_per_pack
    CHECK (units_per_pack IS NULL OR units_per_pack >= 2);

ALTER TABLE clinical.inventory_items ADD COLUMN IF NOT EXISTS pack_size_set_at TIMESTAMP;

CREATE TABLE IF NOT EXISTS clinical.pharmacy_price_changes (
    id                 UUID           NOT NULL DEFAULT gen_random_uuid(),
    hospital_id        UUID           NOT NULL,
    pharmacy_id        UUID           NOT NULL,
    inventory_item_id  UUID           NOT NULL,
    old_price          NUMERIC(12, 2),
    new_price          NUMERIC(12, 2),
    reason             VARCHAR(500)   NOT NULL,
    changed_by         UUID           NOT NULL,
    changed_at         TIMESTAMP      NOT NULL,
    created_at         TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP      NOT NULL DEFAULT now(),

    CONSTRAINT pk_pharmacy_price_changes PRIMARY KEY (id),
    CONSTRAINT fk_pprice_hospital        FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    CONSTRAINT fk_pprice_pharmacy        FOREIGN KEY (pharmacy_id) REFERENCES clinical.pharmacies (id),
    CONSTRAINT fk_pprice_item            FOREIGN KEY (inventory_item_id) REFERENCES clinical.inventory_items (id),
    CONSTRAINT ck_pprice_old             CHECK (old_price IS NULL OR old_price >= 0),
    CONSTRAINT ck_pprice_new             CHECK (new_price IS NULL OR new_price >= 0)
);

CREATE INDEX IF NOT EXISTS idx_pprice_item_changed ON clinical.pharmacy_price_changes (inventory_item_id, changed_at);
CREATE INDEX IF NOT EXISTS idx_pprice_hospital ON clinical.pharmacy_price_changes (hospital_id);

ALTER TABLE clinical.dispenses ADD COLUMN IF NOT EXISTS billing_deferred_reason VARCHAR(500);
ALTER TABLE clinical.dispenses ADD COLUMN IF NOT EXISTS billing_deferred_by UUID;
ALTER TABLE clinical.dispenses ADD COLUMN IF NOT EXISTS billing_deferred_at TIMESTAMP;

CREATE TABLE IF NOT EXISTS clinical.pharmacy_refunds (
    id                     UUID           NOT NULL DEFAULT gen_random_uuid(),
    hospital_id            UUID           NOT NULL,
    pharmacy_id            UUID           NOT NULL,
    return_transaction_id  UUID           NOT NULL,
    sale_id                UUID,
    dispense_id            UUID,
    quantity               NUMERIC(12, 2) NOT NULL,
    amount                 NUMERIC(12, 2) NOT NULL,
    method                 VARCHAR(30)    NOT NULL,
    mobile_money_operator  VARCHAR(20),
    reference              VARCHAR(120),
    reason                 VARCHAR(500)   NOT NULL,
    status                 VARCHAR(20)    NOT NULL,
    requested_by           UUID           NOT NULL,
    requested_at           TIMESTAMP      NOT NULL,
    decided_by             UUID,
    decided_at             TIMESTAMP,
    decision_reason        VARCHAR(500),
    paid_by                UUID,
    paid_at                TIMESTAMP,
    cancelled_by           UUID,
    cancelled_at           TIMESTAMP,
    cancel_reason          VARCHAR(500),
    created_at             TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at             TIMESTAMP      NOT NULL DEFAULT now(),

    CONSTRAINT pk_pharmacy_refunds      PRIMARY KEY (id),
    CONSTRAINT fk_prefund_hospital      FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    CONSTRAINT fk_prefund_pharmacy      FOREIGN KEY (pharmacy_id) REFERENCES clinical.pharmacies (id),
    CONSTRAINT fk_prefund_return_tx     FOREIGN KEY (return_transaction_id) REFERENCES clinical.stock_transactions (id),
    CONSTRAINT fk_prefund_sale          FOREIGN KEY (sale_id) REFERENCES clinical.pharmacy_sales (id),
    CONSTRAINT fk_prefund_dispense      FOREIGN KEY (dispense_id) REFERENCES clinical.dispenses (id),
    CONSTRAINT ck_prefund_amount        CHECK (amount > 0 AND amount = trunc(amount)),
    CONSTRAINT ck_prefund_quantity      CHECK (quantity > 0),
    CONSTRAINT ck_prefund_status        CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'PAID', 'CANCELLED')),
    CONSTRAINT ck_prefund_method        CHECK (method IN ('CASH', 'MOBILE_MONEY', 'INSURANCE')),
    CONSTRAINT ck_prefund_source        CHECK ((sale_id IS NULL) <> (dispense_id IS NULL)),
    CONSTRAINT ck_prefund_four_eyes     CHECK (decided_by IS NULL OR decided_by <> requested_by)
);

CREATE INDEX IF NOT EXISTS idx_prefund_hospital_status ON clinical.pharmacy_refunds (hospital_id, status);
CREATE INDEX IF NOT EXISTS idx_prefund_pharmacy_paid ON clinical.pharmacy_refunds (pharmacy_id, paid_at);
CREATE INDEX IF NOT EXISTS idx_prefund_sale ON clinical.pharmacy_refunds (sale_id);
CREATE INDEX IF NOT EXISTS idx_prefund_dispense ON clinical.pharmacy_refunds (dispense_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_prefund_live_return
    ON clinical.pharmacy_refunds (return_transaction_id)
    WHERE status IN ('REQUESTED', 'APPROVED', 'PAID');
