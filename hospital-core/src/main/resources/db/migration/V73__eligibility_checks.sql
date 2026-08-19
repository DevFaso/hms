-- V73: Real-time eligibility / prior-auth API (P1 #12 follow-up #4 — items 4/5/6)
--
-- Persists each call to a public-payer scheme (NHIS, NHIA, CNAMGS, mutuelle, …)
-- and the optional prior-auth answer. One row per call; the most-recent row per
-- (patient_id, scheme, check_type) is the authoritative current state.
--
-- @Version (BIGINT NOT NULL DEFAULT 0) protects against the rare race where two
-- clinicians submit a check simultaneously and the latter mutates an older
-- snapshot — same pattern as V72 (general_referrals.version).
--
-- Indexes: idx_elig_patient powers the patient-detail timeline; the composite
-- (patient_id, scheme, completed_at) drives the "fresh answer?" lookup on the
-- encounter and checkout screens.
--
-- Additive only; pure DDL.

CREATE TABLE IF NOT EXISTS clinical.eligibility_checks (
    id                      UUID            PRIMARY KEY,
    created_at              TIMESTAMP       NOT NULL,
    updated_at              TIMESTAMP       NOT NULL,
    patient_id              UUID            NOT NULL,
    hospital_id             UUID            NOT NULL,
    patient_insurance_id    UUID,
    scheme                  VARCHAR(24)     NOT NULL,
    check_type              VARCHAR(16)     NOT NULL,
    member_id               VARCHAR(64),
    service_code            VARCHAR(32),
    requested_at            TIMESTAMP       NOT NULL,
    completed_at            TIMESTAMP,
    status                  VARCHAR(16)     NOT NULL,
    response_code           VARCHAR(32),
    payer_response_text     TEXT,
    copay_amount            NUMERIC(12, 2),
    copay_currency          VARCHAR(8),
    prior_auth_required     BOOLEAN,
    prior_auth_number       VARCHAR(64),
    valid_until             DATE,
    error_message           TEXT,
    requested_by_user_id    UUID,
    version                 BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_elig_patient            FOREIGN KEY (patient_id)           REFERENCES clinical.patients (id),
    CONSTRAINT fk_elig_hospital           FOREIGN KEY (hospital_id)          REFERENCES hospital.hospitals (id),
    CONSTRAINT fk_elig_patient_insurance  FOREIGN KEY (patient_insurance_id) REFERENCES clinical.patient_insurances (id),
    CONSTRAINT fk_elig_requested_by       FOREIGN KEY (requested_by_user_id) REFERENCES security.users (id)
);

CREATE INDEX IF NOT EXISTS idx_elig_patient
    ON clinical.eligibility_checks (patient_id);

CREATE INDEX IF NOT EXISTS idx_elig_hospital
    ON clinical.eligibility_checks (hospital_id);

CREATE INDEX IF NOT EXISTS idx_elig_scheme
    ON clinical.eligibility_checks (scheme);

CREATE INDEX IF NOT EXISTS idx_elig_patient_scheme_completed
    ON clinical.eligibility_checks (patient_id, scheme, completed_at);

-- Rollback (manual only):
--   DROP INDEX IF EXISTS clinical.idx_elig_patient_scheme_completed;
--   DROP INDEX IF EXISTS clinical.idx_elig_scheme;
--   DROP INDEX IF EXISTS clinical.idx_elig_hospital;
--   DROP INDEX IF EXISTS clinical.idx_elig_patient;
--   DROP TABLE IF EXISTS clinical.eligibility_checks;
