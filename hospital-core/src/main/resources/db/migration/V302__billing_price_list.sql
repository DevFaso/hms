-- V302 (billing-money go-live plan PR 2, scorecard M1): each hospital's own
-- price list ("grille tarifaire"), with effective-dated price versions.
--
-- Until now a line took its price only from a clinical.treatments row whose
-- name matched, and finance roles had no way to enter a tariff at all
-- (POST /treatments is doctor-only, no screen calls it). This adds the
-- catalogue the hospital admin and the accountant maintain (owner decision
-- D-1, 2026-11-15: never the cashier, never the platform super-admin alone).
-- Nothing reads it at checkout yet: the tariff resolver is PR 3 (V303).
--
-- billing.price_list_items  - one tariff entry: the hospital's own code, the
--   label as typed (shown untranslated, D-8), a category, and an optional link
--   (link_type + link_key) a charge source will match in PR 3:
--     SYSTEM               CONSULTATION_DEFAULT | BED_DAY | DELIVERY_VAGINAL | DELIVERY_CAESAREAN
--     LAB_TEST_DEFINITION  the lab test definition's id
--     PROCEDURE_CODE       the procedure order's code
--     IMAGING_STUDY        <modality>:<study type, lower case, no accents>
--     TRANSFUSION_PRODUCT  a BloodProductType name
--     TREATMENT            a clinical.treatments id of the same hospital
--   One ACTIVE item per (hospital, link): a charge never has two prices.
--   Deactivating keeps the item and its history, with who, when and why.
--
-- billing.price_list_prices - the price versions of an item. Insert-only: no
--   endpoint edits or deletes one. The only update is the service setting
--   effective_to, under a row lock on the item, when a newer version starts
--   (effective_to = new start - 1), or when a version that has not started yet
--   is replaced (effective_to = its own start - 1: an empty range that is never
--   in force, kept in the history). Whole francs, >= 0. A posted invoice line
--   copies its unit price and is never rewritten by a new version (D-4).
--   UNIQUE(item, effective_from) holds among versions that can be in force
--   (a replaced one may share its start with the one that replaced it), and an
--   item has at most one open-ended version.
--   import_id is filled by the CSV import (PR 4, V304), which adds its FK.
--
-- Idempotent (IF NOT EXISTS, CHECKs created with their tables). Plain DDL, no
-- DO block. Rollback: none declared (forward-only, both tables are new).
-- V301-V319 are reserved for the billing-money plan.

CREATE SCHEMA IF NOT EXISTS billing;

CREATE TABLE IF NOT EXISTS billing.price_list_items (
    id                  UUID          NOT NULL DEFAULT gen_random_uuid(),
    hospital_id         UUID          NOT NULL,
    code                VARCHAR(40)   NOT NULL,
    label               VARCHAR(255)  NOT NULL,
    category            VARCHAR(30)   NOT NULL,
    link_type           VARCHAR(40),
    link_key            VARCHAR(255),
    active              BOOLEAN       NOT NULL DEFAULT TRUE,
    deactivated_at      TIMESTAMP,
    deactivated_by      UUID,
    deactivation_reason VARCHAR(500),
    created_by          UUID,
    updated_by          UUID,
    created_at          TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_price_list_items           PRIMARY KEY (id),
    CONSTRAINT fk_price_list_item_hospital   FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    CONSTRAINT uq_price_list_item_code       UNIQUE (hospital_id, code),
    -- Target of the versions' composite FK: a version always carries its item's hospital.
    CONSTRAINT uq_price_list_item_id_hospital UNIQUE (id, hospital_id),
    CONSTRAINT ck_price_list_item_code       CHECK (code = upper(btrim(code)) AND length(code) > 0),
    CONSTRAINT ck_price_list_item_label      CHECK (length(btrim(label)) > 0),
    CONSTRAINT ck_price_list_item_category   CHECK (category IN ('CONSULTATION', 'LAB_TEST', 'IMAGING',
        'PROCEDURE', 'BED_DAY', 'TRANSFUSION', 'DELIVERY', 'OTHER_SERVICE')),
    CONSTRAINT ck_price_list_item_link_type  CHECK (link_type IS NULL OR link_type IN ('SYSTEM',
        'LAB_TEST_DEFINITION', 'PROCEDURE_CODE', 'IMAGING_STUDY', 'TRANSFUSION_PRODUCT', 'TREATMENT')),
    CONSTRAINT ck_price_list_item_link_pair  CHECK ((link_type IS NULL) = (link_key IS NULL)),
    CONSTRAINT ck_price_list_item_deactivated CHECK (active
        OR (deactivated_at IS NOT NULL AND deactivation_reason IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_price_list_item_hospital_category
    ON billing.price_list_items (hospital_id, category);

-- One active item per link at a hospital: a charge source never finds two prices.
CREATE UNIQUE INDEX IF NOT EXISTS uq_price_list_item_active_link
    ON billing.price_list_items (hospital_id, link_type, link_key)
    WHERE active AND link_type IS NOT NULL;

CREATE TABLE IF NOT EXISTS billing.price_list_prices (
    id                  UUID           NOT NULL DEFAULT gen_random_uuid(),
    hospital_id         UUID           NOT NULL,
    item_id             UUID           NOT NULL,
    unit_price          NUMERIC(12, 2) NOT NULL,
    effective_from      DATE           NOT NULL,
    effective_to        DATE,
    change_reason       VARCHAR(500),
    created_by          UUID,
    import_id           UUID,
    created_at          TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP      NOT NULL DEFAULT now(),

    CONSTRAINT pk_price_list_prices          PRIMARY KEY (id),
    CONSTRAINT fk_price_list_price_hospital  FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    CONSTRAINT fk_price_list_price_item      FOREIGN KEY (item_id, hospital_id)
        REFERENCES billing.price_list_items (id, hospital_id),
    CONSTRAINT ck_price_list_price_whole     CHECK (unit_price >= 0 AND unit_price = trunc(unit_price)),
    -- effective_to = effective_from - 1 marks a version replaced before it started.
    CONSTRAINT ck_price_list_price_range     CHECK (effective_to IS NULL OR effective_to >= effective_from - 1)
);

CREATE INDEX IF NOT EXISTS idx_price_list_price_item
    ON billing.price_list_prices (item_id, effective_from);
CREATE INDEX IF NOT EXISTS idx_price_list_price_hospital
    ON billing.price_list_prices (hospital_id);

-- One version per start date among those that can be in force.
CREATE UNIQUE INDEX IF NOT EXISTS uq_price_list_price_item_from
    ON billing.price_list_prices (item_id, effective_from)
    WHERE effective_to IS NULL OR effective_to >= effective_from;

-- At most one open-ended version per item.
CREATE UNIQUE INDEX IF NOT EXISTS uq_price_list_price_item_open
    ON billing.price_list_prices (item_id)
    WHERE effective_to IS NULL;
