-- V68: P1 #11 — DHIS2 ADX (Aggregate Data Exchange) export
--
-- Lets each hospital push aggregate clinical metrics (v0 scope =
-- immunization counts) to a DHIS2 instance using the IHE / HL7 ADX
-- 1.0 XML payload posted at /api/dataValueSets.
--
-- Four tables under a new `integration` schema so the per-tenant
-- export plumbing stays out of `clinical.` and `platform.` and can
-- grow (FHIR Bulk, OpenHIE, partner pushes) without crowding either:
--
--   1. dhis2_facility_config       per-hospital DHIS2 endpoint + auth
--   2. dhis2_dataelement_mapping   HMS concept code -> DHIS2 UID lookup
--   3. dhis2_export_run            one row per triggered export
--   4. dhis2_export_outbox         one row per (period, orgUnit,
--                                  dataElement, categoryOptionCombo)
--                                  value sent
--
-- Hard rules baked into schema:
--
-- * Auth secrets are NEVER stored. `auth_secret_env_var` only holds
--   the *name* of an environment variable; the secret value is
--   resolved at push time. No CHECK can fully prevent misuse so the
--   service layer enforces "no plaintext that looks like a secret"
--   on writes.
--
-- * Outbox uniqueness on (run_id, period_iso, org_unit_uid,
--   dataelement_uid, category_option_combo_uid) guarantees replays
--   are idempotent — the orchestrator can resend the exact same
--   ADX payload without duplicating rows.
--
-- * `hospital.hospitals.dhis2_org_unit_uid` is nullable: hospitals
--   that don't export keep the column null, hospitals that do bind
--   their facility to a DHIS2 organisation-unit UID (11-char DHIS2
--   convention).
--
-- Additive only; pure DDL.

CREATE SCHEMA IF NOT EXISTS integration;

-- =========================================================================
-- 1. Per-facility DHIS2 endpoint + auth pointer
-- =========================================================================
CREATE TABLE integration.dhis2_facility_config (
    id                     UUID PRIMARY KEY,
    hospital_id            UUID NOT NULL,
    base_url               VARCHAR(512) NOT NULL,
    auth_mode              VARCHAR(16) NOT NULL,
    auth_secret_env_var    VARCHAR(128) NOT NULL,
    default_period_type    VARCHAR(16) NOT NULL,
    default_dataset_uid    VARCHAR(11),
    last_export_at         TIMESTAMP WITHOUT TIME ZONE,
    is_active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at             TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT fk_dhis2_facility_config_hospital
        FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    CONSTRAINT uq_dhis2_facility_config_hospital
        UNIQUE (hospital_id),
    CONSTRAINT chk_dhis2_facility_auth_mode
        CHECK (auth_mode IN ('PAT', 'BASIC')),
    CONSTRAINT chk_dhis2_facility_period_type
        CHECK (default_period_type IN ('MONTHLY', 'WEEKLY', 'YEARLY')),
    CONSTRAINT chk_dhis2_facility_secret_is_env_var
        CHECK (auth_secret_env_var ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT chk_dhis2_facility_default_dataset_uid_format
        CHECK (default_dataset_uid IS NULL OR default_dataset_uid ~ '^[A-Za-z][A-Za-z0-9]{10}$')
);

CREATE INDEX idx_dhis2_facility_config_active
    ON integration.dhis2_facility_config (hospital_id)
    WHERE is_active = TRUE;

-- =========================================================================
-- 2. HMS concept-code -> DHIS2 dataElement mapping
-- =========================================================================
CREATE TABLE integration.dhis2_dataelement_mapping (
    id                              UUID PRIMARY KEY,
    hospital_id                     UUID NOT NULL,
    hms_concept_system              VARCHAR(255) NOT NULL,
    hms_concept_code                VARCHAR(64)  NOT NULL,
    dhis2_dataelement_uid           VARCHAR(11)  NOT NULL,
    dhis2_category_option_combo_uid VARCHAR(11),
    period_type                     VARCHAR(16)  NOT NULL,
    dataset_uid                     VARCHAR(11)  NOT NULL,
    is_active                       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                      TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at                      TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT fk_dhis2_mapping_hospital
        FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    CONSTRAINT uq_dhis2_mapping_per_dataset
        UNIQUE (hospital_id, hms_concept_system, hms_concept_code, dataset_uid),
    CONSTRAINT chk_dhis2_mapping_period_type
        CHECK (period_type IN ('MONTHLY', 'WEEKLY', 'YEARLY')),
    CONSTRAINT chk_dhis2_mapping_dataelement_uid_format
        CHECK (dhis2_dataelement_uid ~ '^[A-Za-z][A-Za-z0-9]{10}$'),
    CONSTRAINT chk_dhis2_mapping_dataset_uid_format
        CHECK (dataset_uid ~ '^[A-Za-z][A-Za-z0-9]{10}$')
);

-- Hot-path lookup: aggregator resolves codes per (hospital, dataset, active).
CREATE INDEX idx_dhis2_mapping_lookup
    ON integration.dhis2_dataelement_mapping (hospital_id, dataset_uid, hms_concept_system)
    WHERE is_active = TRUE;

-- =========================================================================
-- 3. One row per triggered export
-- =========================================================================
CREATE TABLE integration.dhis2_export_run (
    id                      UUID PRIMARY KEY,
    hospital_id             UUID NOT NULL,
    dataset_uid             VARCHAR(11) NOT NULL,
    period_iso              VARCHAR(16) NOT NULL,
    triggered_by_staff_id   UUID,
    started_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    completed_at            TIMESTAMP WITHOUT TIME ZONE,
    status                  VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    value_count             INTEGER     NOT NULL DEFAULT 0,
    skipped_count           INTEGER     NOT NULL DEFAULT 0,
    http_status             INTEGER,
    error_message           VARCHAR(2048),
    request_id              UUID        NOT NULL,
    created_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT fk_dhis2_run_hospital
        FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    CONSTRAINT chk_dhis2_run_status
        CHECK (status IN ('PENDING', 'SUCCESS', 'PARTIAL', 'FAILED')),
    CONSTRAINT uq_dhis2_run_request_id
        UNIQUE (request_id)
);

-- Per-hospital "what did we send and when" view, latest-first.
CREATE INDEX idx_dhis2_run_hospital_started
    ON integration.dhis2_export_run (hospital_id, started_at DESC);

-- Operations: find currently-pending runs for reconciliation.
CREATE INDEX idx_dhis2_run_pending
    ON integration.dhis2_export_run (started_at DESC)
    WHERE status = 'PENDING';

-- =========================================================================
-- 4. Outbox: one row per data value posted in a run
-- =========================================================================
CREATE TABLE integration.dhis2_export_outbox (
    id                              UUID PRIMARY KEY,
    run_id                          UUID NOT NULL,
    period_iso                      VARCHAR(16) NOT NULL,
    org_unit_uid                    VARCHAR(11) NOT NULL,
    dataelement_uid                 VARCHAR(11) NOT NULL,
    category_option_combo_uid       VARCHAR(11),
    data_value                      VARCHAR(64) NOT NULL,
    status                          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts                        INTEGER     NOT NULL DEFAULT 0,
    last_error                      VARCHAR(1024),
    sent_at                         TIMESTAMP WITHOUT TIME ZONE,
    created_at                      TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at                      TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT fk_dhis2_outbox_run
        FOREIGN KEY (run_id) REFERENCES integration.dhis2_export_run (id),
    CONSTRAINT chk_dhis2_outbox_status
        CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT uq_dhis2_outbox_value
        UNIQUE (run_id, period_iso, org_unit_uid, dataelement_uid, category_option_combo_uid)
);

-- Reconciliation lookup: walk all outbox rows for a run.
CREATE INDEX idx_dhis2_outbox_run
    ON integration.dhis2_export_outbox (run_id);

-- Operations: find unfinished outbox entries for retry.
CREATE INDEX idx_dhis2_outbox_pending
    ON integration.dhis2_export_outbox (run_id, status)
    WHERE status IN ('PENDING', 'FAILED');

-- =========================================================================
-- 5. hospital.hospitals.dhis2_org_unit_uid (nullable)
-- =========================================================================
ALTER TABLE hospital.hospitals
    ADD COLUMN IF NOT EXISTS dhis2_org_unit_uid VARCHAR(11);

ALTER TABLE hospital.hospitals
    ADD CONSTRAINT chk_hospitals_dhis2_org_unit_uid_format
    CHECK (dhis2_org_unit_uid IS NULL OR dhis2_org_unit_uid ~ '^[A-Za-z][A-Za-z0-9]{10}$');
