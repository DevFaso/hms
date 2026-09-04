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
-- has zero history rows. The address line is TEXT because the app encrypts
-- it at rest (EncryptedStringConverter), exactly like the source column.
--
-- The preferred-language third of item 38 already shipped (PatientLanguage
-- + PatientLocaleResolver, read by the reminder sweeps) — this migration
-- deliberately adds nothing for it.
--
-- Rollback:
--   ALTER TABLE clinical.patients DROP COLUMN ethnicity;
--   DROP TABLE clinical.patient_address_history;
-- =============================================================================

-- TEXT, not VARCHAR(100): self-reported free text is patient-specific
-- narrative, so the app encrypts it (EncryptedStringConverter) and the
-- AES-GCM + Base64 payload outgrows the 100-character plaintext cap the
-- DTOs enforce.
ALTER TABLE clinical.patients
    ADD COLUMN IF NOT EXISTS ethnicity TEXT;

COMMENT ON COLUMN clinical.patients.ethnicity IS
    'Self-reported, free text, optional (Tier 2 item 38). Deliberately not '
    'an enum — the patient''s own words, not a schema-invented taxonomy.';

-- The history row is a human-readable snapshot, not a column-for-column
-- clone of clinical.patients: the composed address line (encrypted, like
-- its source) plus city/country for coarse filtering. Structured parts of
-- a SUPERSEDED address have no query surface -- the composed line is what
-- a clinician reads.
CREATE TABLE IF NOT EXISTS clinical.patient_address_history (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    patient_id     UUID         NOT NULL,
    address        TEXT,
    city           VARCHAR(100),
    country        VARCHAR(100),
    created_by     VARCHAR(255),
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_patient_address_history PRIMARY KEY (id),
    -- CASCADE: history rows are patient-owned; without it the documented
    -- deletePatient path fails on the first patient who ever moved.
    CONSTRAINT fk_addr_hist_patient FOREIGN KEY (patient_id) REFERENCES clinical.patients(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_addr_hist_patient
    ON clinical.patient_address_history (patient_id, created_at);

COMMENT ON TABLE clinical.patient_address_history IS
    'Superseded patient addresses (Tier 2 item 38): each row was the '
    'address valid UNTIL its created_at; the current address lives on '
    'clinical.patients.';
