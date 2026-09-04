-- V151: release-of-information request workflow (Tier 2 item 39b).
--
-- WHY: the other half of item 39. The DISCLOSURE ACCOUNTING (V141) records
-- what left the system; nothing recorded the formal REQUEST that leads to a
-- release. This table is the request row a records desk triages: a patient
-- or an authorised third party asks for a copy, staff fulfil or deny, and
-- the fulfilment emits a PATIENT_EXPORT audit row keyed by patient -- which
-- item 39's whitelist classifies as COPY_RELEASED, so every fulfilled
-- request lands on the patient's own disclosure report automatically.
--
-- Requester identity, purpose, scope and the decision note are TEXT because
-- the app encrypts them at rest (EncryptedStringConverter) -- who is asking
-- for a record and why is itself sensitive.
--
-- version: optimistic lock (the V149 lesson) -- two concurrent decisions on
-- one request must not both report success.
--
-- NO FOREIGN KEY on patient_id at all -- the V141 call, for the V141
-- reason: an ROI request is a legal/administrative record of an exchange
-- with an outside party and must OUTLIVE the patient row. A cascading FK
-- would delete the record with its subject; a plain (RESTRICT) FK would
-- block the purge path instead. The column is a lookup key, not a
-- relationship, and patient_name is an encrypted snapshot taken at intake
-- so the row stays legible after a purge.
--
-- Rollback:
--   DROP TABLE clinical.roi_requests;
-- =============================================================================

CREATE TABLE IF NOT EXISTS clinical.roi_requests (
    id                   UUID         NOT NULL DEFAULT gen_random_uuid(),
    patient_id           UUID         NOT NULL,
    patient_name         TEXT,
    hospital_id          UUID         NOT NULL,
    requester_type       VARCHAR(20)  NOT NULL,
    requester_name       TEXT,
    requester_contact    TEXT,
    purpose              TEXT,
    scope_description    TEXT,
    status               VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    requested_on         DATE         NOT NULL,
    decided_at           TIMESTAMP,
    decided_by_staff_id  UUID,
    decision_note        TEXT,
    version              BIGINT       NOT NULL DEFAULT 0,
    created_by           VARCHAR(255),
    created_at           TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_roi_requests   PRIMARY KEY (id),
    -- patient_id deliberately has NO FK: see the header. Hospitals and
    -- staff are never hard-deleted, so their FKs stay.
    CONSTRAINT fk_roi_hospital   FOREIGN KEY (hospital_id)         REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_roi_decided_by FOREIGN KEY (decided_by_staff_id) REFERENCES hospital.staff(id)
);

CREATE INDEX IF NOT EXISTS idx_roi_patient
    ON clinical.roi_requests (patient_id);
-- The records-desk worklist: pending first-in-first-out per hospital.
CREATE INDEX IF NOT EXISTS idx_roi_worklist
    ON clinical.roi_requests (hospital_id, status, requested_on);

COMMENT ON TABLE clinical.roi_requests IS
    'Release-of-information requests (Tier 2 item 39b): the request side of '
    'the disclosure accounting; fulfilment emits the PATIENT_EXPORT row the '
    'patient-facing report shows as COPY_RELEASED.';
