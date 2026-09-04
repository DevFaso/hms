-- V150: demographics depth (Tier 2 item 38) — ethnicity + address history.
--
-- WHY: the audit found no home for either. Ethnicity is SELF-REPORTED free
-- text on purpose: a fixed enum of ethnic groups would be this schema
-- inventing a taxonomy for a region it has no mandate to classify (same
-- stance as visit cadences in V146 — the person supplies the value).
--
-- Address history keeps the PREVIOUS address whenever a patient's address
-- changes: each row is the address that was valid UNTIL its created_at.
-- The current address stays on clinical.patients; a patient with no moves
-- has zero history rows. Address lines are TEXT because the app encrypts
-- them at rest (EncryptedStringConverter), exactly like the source columns.
--
-- The preferred-language third of item 38 already shipped (PatientLanguage
-- + PatientLocaleResolver, read by the reminder sweeps) — this migration
-- deliberately adds nothing for it.
--
-- Rollback:
--   ALTER TABLE clinical.patients DROP COLUMN ethnicity;
--   DROP TABLE clinical.patient_address_history;
-- =============================================================================

ALTER TABLE clinical.patients
    ADD COLUMN IF NOT EXISTS ethnicity VARCHAR(100);

COMMENT ON COLUMN clinical.patients.ethnicity IS
    'Self-reported, free text, optional (Tier 2 item 38). Deliberately not '
    'an enum — the patient''s own words, not a schema-invented taxonomy.';

CREATE TABLE IF NOT EXISTS clinical.patient_address_history (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    patient_id     UUID         NOT NULL,
    address        TEXT,
    address_line1  TEXT,
    address_line2  TEXT,
    city           VARCHAR(100),
    state          VARCHAR(100),
    zip_code       VARCHAR(100),
    country        VARCHAR(100),
    created_by     VARCHAR(255),
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_patient_address_history PRIMARY KEY (id),
    CONSTRAINT fk_addr_hist_patient FOREIGN KEY (patient_id) REFERENCES clinical.patients(id)
);

CREATE INDEX IF NOT EXISTS idx_addr_hist_patient
    ON clinical.patient_address_history (patient_id, created_at);

COMMENT ON TABLE clinical.patient_address_history IS
    'Superseded patient addresses (Tier 2 item 38): each row was the '
    'address valid UNTIL its created_at; the current address lives on '
    'clinical.patients.';
