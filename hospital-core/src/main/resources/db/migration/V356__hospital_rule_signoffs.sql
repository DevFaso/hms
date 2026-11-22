-- =====================================================================
-- V356: per-hospital sign-off of the provisional clinical and operational
-- rule sets (owner direction 2026-11-21).
--
-- Every rule set built so far is « provisoire » until EACH hospital's
-- named approver (the médecin-chef or the role the registry names) signs
-- it off at onboarding. The rule sets themselves are defined in code
-- (ClinicalRuleRegistry: key, version, source, approver role); this table
-- holds only what a hospital decided about them.
--
--   * One row per sign-off of one rule set, at one version, at one
--     hospital. The signer is usually not a user of the platform: their
--     name and title are recorded as written, and signer_user_id is set
--     only when they are. recorded_by_user_id is the admin who entered it.
--   * status: SIGNED, or REVOKED (revoked_at / _by / revoke_reason). A
--     revoked row is kept; signing again adds a new row.
--   * "Pending" is not stored: a rule set with no SIGNED row at its
--     CURRENT version is pending, so a new version of a rule reopens it
--     without touching any row here.
--   * At most one SIGNED row per hospital, rule set and version
--     (uq_rule_signoff_signed, partial).
--
-- Rule behaviour does not depend on this table: it records a decision and
-- changes the « provisoire » label only.
-- Additive and idempotent: IF NOT EXISTS, no DO block, no backfill.
-- =====================================================================

CREATE TABLE IF NOT EXISTS hospital.hospital_rule_signoffs (
    id                   UUID PRIMARY KEY,
    created_at           TIMESTAMP NOT NULL,
    updated_at           TIMESTAMP NOT NULL,
    hospital_id          UUID NOT NULL,
    rule_key             VARCHAR(64) NOT NULL,
    rule_version         VARCHAR(64) NOT NULL,
    status               VARCHAR(16) NOT NULL,
    signer_name          VARCHAR(200) NOT NULL,
    signer_title         VARCHAR(200) NOT NULL,
    signer_user_id       UUID,
    signed_on            DATE NOT NULL,
    note                 VARCHAR(1000),
    recorded_by_user_id  UUID NOT NULL,
    recorded_at          TIMESTAMP NOT NULL,
    revoked_at           TIMESTAMP,
    revoked_by_user_id   UUID,
    revoke_reason        VARCHAR(1000),
    CONSTRAINT fk_rule_signoff_hospital FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id),
    -- Every RuleSignoffStatus constant (EntitySchemaValidationIT); PENDING is never written.
    CONSTRAINT ck_rule_signoff_status CHECK (status IN ('PENDING', 'SIGNED', 'REVOKED'))
);

CREATE INDEX IF NOT EXISTS idx_rule_signoff_hospital_rule
    ON hospital.hospital_rule_signoffs (hospital_id, rule_key, recorded_at);

CREATE UNIQUE INDEX IF NOT EXISTS uq_rule_signoff_signed
    ON hospital.hospital_rule_signoffs (hospital_id, rule_key, rule_version)
    WHERE status = 'SIGNED';
