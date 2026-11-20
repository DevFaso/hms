-- =====================================================================
-- V347: sensitive-data defaults and the release of a minor's result
-- (policy 2026-11-20, « Burkina clinic policy decisions », row 4).
-- Every rule this serves is PROVISIONAL until a Burkinabè jurist and a
-- clinician sign it off.
--
--   * clinical.proxy_sensitive_releases: a clinician releases ONE sensitive
--     result of a patient under 18 to ONE verified parent or guardian
--     (Loi 030-2008, art. 17 and 18: the parents of a tested minor have a
--     right to the HIV result). The result stays hidden from every proxy
--     until then, and is never blocked for good. The reason is encrypted:
--     it can say why the result is disclosed.
--   * clinical.patient_proxies.relationship_verified_at / _by: staff
--     checked that the holder of a family-access grant is the patient's
--     parent or legal guardian. NULL, the default: not verified, so the
--     grant opens nothing sensitive (today's behaviour for every row).
--   * hospital.hospitals.adolescent_confidentiality_age: from this age to
--     17 a minor's sensitive records are hidden from the parents unless a
--     clinician releases them. NULL means the provisional default, 12,
--     held in code so that a jurist's answer changes one constant.
--   * clinical.medication_catalog_items.sensitivity_category: a medicine
--     that reveals a category (an antiretroviral) whatever visit prescribed
--     it. NULL, the default, keeps today's behaviour. Never seeded: which
--     medicines carry one is each hospital's decision.
--
-- No category is written to any row here: the new SensitivityCategory
-- values are names in VARCHAR(32) columns that carry no CHECK.
-- Additive and idempotent: IF NOT EXISTS, no DO block, no backfill.
-- =====================================================================

CREATE TABLE IF NOT EXISTS clinical.proxy_sensitive_releases (
    id                   UUID PRIMARY KEY,
    created_at           TIMESTAMP NOT NULL,
    updated_at           TIMESTAMP NOT NULL,
    hospital_id          UUID NOT NULL,
    patient_id           UUID NOT NULL,
    proxy_grant_id       UUID NOT NULL,
    proxy_user_id        UUID NOT NULL,
    lab_order_id         UUID NOT NULL,
    sensitivity_category VARCHAR(32) NOT NULL,
    reason               TEXT NOT NULL,
    released_by_user_id  UUID NOT NULL,
    released_at          TIMESTAMP NOT NULL,
    CONSTRAINT fk_proxy_release_hospital FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_proxy_release_patient FOREIGN KEY (patient_id) REFERENCES clinical.patients(id),
    CONSTRAINT fk_proxy_release_grant FOREIGN KEY (proxy_grant_id) REFERENCES clinical.patient_proxies(id),
    CONSTRAINT fk_proxy_release_lab_order FOREIGN KEY (lab_order_id) REFERENCES lab.lab_orders(id),
    CONSTRAINT uq_proxy_release_grant_order UNIQUE (proxy_grant_id, lab_order_id)
);

CREATE INDEX IF NOT EXISTS idx_proxy_release_patient
    ON clinical.proxy_sensitive_releases (patient_id, hospital_id);

ALTER TABLE clinical.patient_proxies
    ADD COLUMN IF NOT EXISTS relationship_verified_at TIMESTAMP;
ALTER TABLE clinical.patient_proxies
    ADD COLUMN IF NOT EXISTS relationship_verified_by_user_id UUID;

ALTER TABLE hospital.hospitals
    ADD COLUMN IF NOT EXISTS adolescent_confidentiality_age INTEGER;
ALTER TABLE hospital.hospitals
    DROP CONSTRAINT IF EXISTS chk_hospital_adolescent_confidentiality_age;
ALTER TABLE hospital.hospitals
    ADD CONSTRAINT chk_hospital_adolescent_confidentiality_age
        CHECK (adolescent_confidentiality_age IS NULL
            OR (adolescent_confidentiality_age >= 0 AND adolescent_confidentiality_age <= 18));

ALTER TABLE clinical.medication_catalog_items
    ADD COLUMN IF NOT EXISTS sensitivity_category VARCHAR(32);
