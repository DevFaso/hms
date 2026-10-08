-- V180: external provider facilities (D5, phase P1 "shared core").
--
-- A private pharmacy or laboratory joins e-Keneya as its own tenant. The
-- tenant unit stays the hospital.hospitals row (decision D-A): a provider is
-- a row whose facility_type is PHARMACY or LABORATORY, so every tenancy
-- mechanism that keys on that row (assignments, the acting scope, the
-- lifecycle gate, the performing lab) works unchanged.
-- Plan: docs/plan/external-provider-organisations-plan.md (PR #828), §6.1.
--
-- 1. hospital.hospitals.facility_type, NOT NULL DEFAULT 'HOSPITAL', so every
--    existing row is a hospital. A CHECK holds the three values.
-- 2. hospital.provider_verifications: the onboarding evidence of a provider.
--    Two layers. The legal identity of the business (the RCCM extract is
--    authoritative; the IFU and CNSS documents must agree with it) and the
--    professional layer (licence or ministry authorisation, the responsible
--    pharmacist or biologist and their Ordre number). These numbers identify
--    a business, not a patient: plain columns, no encryption. No expiry date
--    is required anywhere (licence_expires_on is nullable).
--    created_at / updated_at: the entity extends BaseEntity (V153 -> V154).
--    BaseEntity has no version column.
-- 3. Partial unique indexes: one current (SUBMITTED or VERIFIED) verification
--    per facility; among VERIFIED rows, one (authority, licence) pair, one
--    RCCM and one IFU (one business is one facility in v1). They are NOT
--    declared on the JPA entity: H2 builds tables from the entities and
--    would create FULL unique indexes.
-- 4. A VERIFIED row must carry both consistency confirmations.
-- 5. ROLE_PROVIDER_ADMIN, seeded here only (RoleSeeder is local-only and is
--    not changed), in the shape of V26 and V43.
--
-- The FK to hospital.hospitals has no cascade: tenant purge changes state
-- only today. Whoever ships row-level hospital deletion must delete a
-- facility's verification rows first.
--
-- No DO blocks. Forward-only; harmless to the previous application version
-- (it never reads the new column or table).

ALTER TABLE hospital.hospitals
    ADD COLUMN IF NOT EXISTS facility_type VARCHAR(20) NOT NULL DEFAULT 'HOSPITAL';

ALTER TABLE hospital.hospitals
    ADD CONSTRAINT chk_hospital_facility_type
    CHECK (facility_type IN ('HOSPITAL', 'PHARMACY', 'LABORATORY'));

CREATE INDEX IF NOT EXISTS idx_hospital_facility_type
    ON hospital.hospitals (facility_type);

CREATE TABLE IF NOT EXISTS hospital.provider_verifications (
    id                                    UUID PRIMARY KEY,
    hospital_id                           UUID NOT NULL,
    status                                VARCHAR(20) NOT NULL,
    legal_name                            VARCHAR(255) NOT NULL,
    trade_name                            VARCHAR(255),
    legal_structure                       VARCHAR(50) NOT NULL,
    rccm_number                           VARCHAR(50) NOT NULL,
    ifu_number                            VARCHAR(30) NOT NULL,
    cnss_number                           VARCHAR(30) NOT NULL,
    address_secteur                       VARCHAR(50),
    address_section                       VARCHAR(50),
    address_lot                           VARCHAR(50),
    address_parcelle                      VARCHAR(50),
    address_city                          VARCHAR(100) NOT NULL,
    address_region                        VARCHAR(100) NOT NULL,
    company_phone                         VARCHAR(30) NOT NULL,
    manager_name                          VARCHAR(200) NOT NULL,
    manager_title                         VARCHAR(100) NOT NULL,
    business_started_on                   DATE NOT NULL,
    ifu_matches_rccm                      BOOLEAN NOT NULL DEFAULT FALSE,
    cnss_matches_rccm                     BOOLEAN NOT NULL DEFAULT FALSE,
    licence_number                        VARCHAR(100) NOT NULL,
    licence_authority                     VARCHAR(200) NOT NULL,
    licence_issued_on                     DATE,
    licence_expires_on                    DATE,
    responsible_professional_name         VARCHAR(200) NOT NULL,
    responsible_professional_registration VARCHAR(100) NOT NULL,
    evidence_note                         TEXT,
    decided_by_user_id                    UUID,
    decided_at                            TIMESTAMP,
    decision_reason                       VARCHAR(1000),
    created_at                            TIMESTAMP NOT NULL,
    updated_at                            TIMESTAMP NOT NULL,
    CONSTRAINT fk_provider_verification_hospital FOREIGN KEY (hospital_id)
        REFERENCES hospital.hospitals (id),
    CONSTRAINT chk_provider_verification_status
        CHECK (status IN ('SUBMITTED', 'VERIFIED', 'REJECTED', 'REVOKED')),
    CONSTRAINT chk_provider_verified_consistent
        CHECK (status <> 'VERIFIED' OR (ifu_matches_rccm AND cnss_matches_rccm))
);

CREATE INDEX IF NOT EXISTS idx_provider_verification_hospital
    ON hospital.provider_verifications (hospital_id, created_at);

CREATE UNIQUE INDEX IF NOT EXISTS uq_provider_verification_current
    ON hospital.provider_verifications (hospital_id)
    WHERE status IN ('SUBMITTED', 'VERIFIED');

CREATE UNIQUE INDEX IF NOT EXISTS uq_provider_licence_verified
    ON hospital.provider_verifications (licence_authority, licence_number)
    WHERE status = 'VERIFIED';

CREATE UNIQUE INDEX IF NOT EXISTS uq_provider_rccm_verified
    ON hospital.provider_verifications (rccm_number)
    WHERE status = 'VERIFIED';

CREATE UNIQUE INDEX IF NOT EXISTS uq_provider_ifu_verified
    ON hospital.provider_verifications (ifu_number)
    WHERE status = 'VERIFIED';

INSERT INTO "security".roles (id, code, name, description, created_at, updated_at) VALUES
    (gen_random_uuid(), 'ROLE_PROVIDER_ADMIN', 'ROLE_PROVIDER_ADMIN',
     'Administrator of an external provider facility (pharmacy or laboratory)', NOW(), NOW())
ON CONFLICT DO NOTHING;
