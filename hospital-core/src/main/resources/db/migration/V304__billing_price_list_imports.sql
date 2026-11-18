-- V304 (billing-money go-live plan PR 4, scorecard M1): a trace of every
-- price-list spreadsheet checked or applied through the CSV import.
--
-- billing.price_list_imports - one row per "Vérifier le fichier" (DRY_RUN) and
--   per applied import (APPLIED): the file name as uploaded, the SHA-256 of
--   its bytes, the row counts (rows read, new items, new prices, unchanged,
--   refused), who and when. An applied file is recognised by its SHA-256 at
--   the same hospital: applying it again is reported "déjà importé" and writes
--   nothing (partial UNIQUE below, which also settles two concurrent applies).
--   A refused apply writes nothing at all (all or nothing).
--
-- billing.price_list_prices.import_id (V302) now references the import that
--   wrote the version, at the same hospital (composite FK, as the versions'
--   own FK to their item).
--
-- Idempotent (IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before the ADD). Plain
-- DDL, no DO block. Rollback: none declared (forward-only, the table is new).
-- V301-V319 are reserved for the billing-money plan.

CREATE TABLE IF NOT EXISTS billing.price_list_imports (
    id                  UUID          NOT NULL DEFAULT gen_random_uuid(),
    hospital_id         UUID          NOT NULL,
    file_name           VARCHAR(255)  NOT NULL,
    sha256              VARCHAR(64)   NOT NULL,
    status              VARCHAR(20)   NOT NULL,
    total_rows          INTEGER       NOT NULL DEFAULT 0,
    created_count       INTEGER       NOT NULL DEFAULT 0,
    new_price_count     INTEGER       NOT NULL DEFAULT 0,
    unchanged_count     INTEGER       NOT NULL DEFAULT 0,
    refused_count       INTEGER       NOT NULL DEFAULT 0,
    imported_by         UUID,
    created_at          TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_price_list_imports            PRIMARY KEY (id),
    CONSTRAINT fk_price_list_import_hospital    FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    -- Target of the versions' composite FK: a version and its import belong to one hospital.
    CONSTRAINT uq_price_list_import_id_hospital UNIQUE (id, hospital_id),
    CONSTRAINT ck_price_list_import_status      CHECK (status IN ('DRY_RUN', 'APPLIED')),
    CONSTRAINT ck_price_list_import_sha256      CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_price_list_import_counts      CHECK (total_rows >= 0 AND created_count >= 0
        AND new_price_count >= 0 AND unchanged_count >= 0 AND refused_count >= 0),
    -- An applied import refused nothing (all or nothing).
    CONSTRAINT ck_price_list_import_applied     CHECK (status <> 'APPLIED' OR refused_count = 0)
);

CREATE INDEX IF NOT EXISTS idx_price_list_import_hospital_created
    ON billing.price_list_imports (hospital_id, created_at);

-- The same file applies once per hospital.
CREATE UNIQUE INDEX IF NOT EXISTS uq_price_list_import_applied_sha256
    ON billing.price_list_imports (hospital_id, sha256)
    WHERE status = 'APPLIED';

ALTER TABLE billing.price_list_prices DROP CONSTRAINT IF EXISTS fk_price_list_price_import;
ALTER TABLE billing.price_list_prices ADD CONSTRAINT fk_price_list_price_import
    FOREIGN KEY (import_id, hospital_id) REFERENCES billing.price_list_imports (id, hospital_id);

CREATE INDEX IF NOT EXISTS idx_price_list_price_import
    ON billing.price_list_prices (import_id)
    WHERE import_id IS NOT NULL;
