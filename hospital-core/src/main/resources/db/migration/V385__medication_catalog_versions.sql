-- V385 (go-live scorecard line D2): the formulary and the dose limits get the traceable, per-hospital,
-- versioned approval the laboratory definitions already have.
--
-- 1. clinical.medication_catalog_versions: one row per version of a catalogue entry (kind FORMULARY) or of its
--    dose limits (kind DOSE_LIMIT) at one hospital: what is proposed and what was in force (JSON snapshots of
--    catalogue configuration, never patient data), who proposed it, when and why, and who approved, refused or
--    withdrew it, when and why. hospital_id NULL is a platform entry (the national list). catalog_item_id NULL
--    is a proposed new entry: the catalogue row is created by the approval.
-- 2. clinical.medication_catalog_items: the number of the approved version in force, per kind. NULL on every
--    existing row: nothing is backfilled, because no second person approved them. They keep working exactly
--    as before and read as awaiting their first approval.
-- Plain statements, no DO block. Idempotent.

CREATE TABLE IF NOT EXISTS clinical.medication_catalog_versions (
    id               UUID          NOT NULL DEFAULT gen_random_uuid(),
    created_at       TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP     NOT NULL DEFAULT now(),
    hospital_id      UUID,
    catalog_item_id  UUID,
    kind             VARCHAR(20)   NOT NULL,
    change_type      VARCHAR(20)   NOT NULL,
    version_number   INTEGER       NOT NULL,
    status           VARCHAR(30)   NOT NULL,
    item_label       VARCHAR(700)  NOT NULL,
    payload          TEXT          NOT NULL,
    previous_payload TEXT,
    change_reason    VARCHAR(1000) NOT NULL,
    proposed_by      UUID          NOT NULL,
    proposed_at      TIMESTAMP     NOT NULL,
    decided_by       UUID,
    decided_at       TIMESTAMP,
    decision_reason  VARCHAR(1000),
    decision_basis   VARCHAR(40),
    CONSTRAINT pk_med_catalog_versions PRIMARY KEY (id),
    CONSTRAINT fk_med_catalog_version_hospital FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    CONSTRAINT fk_med_catalog_version_item FOREIGN KEY (catalog_item_id)
        REFERENCES clinical.medication_catalog_items (id),
    CONSTRAINT ck_med_catalog_version_kind CHECK (kind IN ('FORMULARY', 'DOSE_LIMIT')),
    CONSTRAINT ck_med_catalog_version_change CHECK (change_type IN ('CREATE', 'UPDATE', 'RATIFY')),
    CONSTRAINT ck_med_catalog_version_status
        CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'SUPERSEDED', 'WITHDRAWN')),
    CONSTRAINT ck_med_catalog_version_number CHECK (version_number >= 1),
    -- Only a new entry has no catalogue row yet, and only until it is approved.
    CONSTRAINT ck_med_catalog_version_item CHECK (
        catalog_item_id IS NOT NULL
        OR (change_type = 'CREATE' AND status IN ('PENDING_APPROVAL', 'REJECTED', 'WITHDRAWN'))),
    -- A decided version names who decided and when; a refusal says why.
    CONSTRAINT ck_med_catalog_version_decided CHECK (
        (status = 'PENDING_APPROVAL' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status <> 'PENDING_APPROVAL' AND decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT ck_med_catalog_version_refusal CHECK (
        status <> 'REJECTED' OR length(trim(coalesce(decision_reason, ''))) > 0),
    -- An approval or a refusal says on what authority it was made (a pharmacist, the medecin-chef, the
    -- medecin-chef because the hospital had no pharmacist, a platform administrator).
    CONSTRAINT ck_med_catalog_version_basis CHECK (
        decision_basis IS NULL
        OR decision_basis IN ('PHARMACIST', 'MEDICAL_LEAD', 'MEDICAL_LEAD_NO_PHARMACIST', 'PLATFORM_ADMIN')),
    CONSTRAINT ck_med_catalog_version_basis_decided CHECK (
        status NOT IN ('APPROVED', 'SUPERSEDED', 'REJECTED') OR decision_basis IS NOT NULL),
    -- The fallback is for the formulary only: dose limits are the medecin-chef's in their own right.
    CONSTRAINT ck_med_catalog_version_basis_fallback CHECK (
        decision_basis IS DISTINCT FROM 'MEDICAL_LEAD_NO_PHARMACIST' OR kind = 'FORMULARY'),
    -- Four eyes, in the table too: the approver is never the author.
    CONSTRAINT ck_med_catalog_version_four_eyes CHECK (
        status NOT IN ('APPROVED', 'SUPERSEDED') OR decided_by <> proposed_by)
);

CREATE INDEX IF NOT EXISTS idx_med_catalog_version_hospital_status
    ON clinical.medication_catalog_versions (hospital_id, status);
CREATE INDEX IF NOT EXISTS idx_med_catalog_version_item
    ON clinical.medication_catalog_versions (catalog_item_id, kind, version_number);
-- One version in progress per entry and kind, and one in force.
CREATE UNIQUE INDEX IF NOT EXISTS uq_med_catalog_version_pending
    ON clinical.medication_catalog_versions (catalog_item_id, kind)
    WHERE status = 'PENDING_APPROVAL' AND catalog_item_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_med_catalog_version_in_force
    ON clinical.medication_catalog_versions (catalog_item_id, kind)
    WHERE status = 'APPROVED';
CREATE UNIQUE INDEX IF NOT EXISTS uq_med_catalog_version_number
    ON clinical.medication_catalog_versions (catalog_item_id, kind, version_number)
    WHERE catalog_item_id IS NOT NULL;

ALTER TABLE clinical.medication_catalog_items
    ADD COLUMN IF NOT EXISTS formulary_approved_version INTEGER;
ALTER TABLE clinical.medication_catalog_items
    ADD COLUMN IF NOT EXISTS dose_limit_approved_version INTEGER;
