-- V126: registration extras (P3 item 21) — patient photo columns,
-- consent-to-treat records, guarantor accounts.
--
-- WHY:
--  * Photo: no photo/image field exists anywhere on clinical.patients; every
--    patient "avatar" in the portal is initials-only. The columns store a
--    storage path + content type — DELIBERATELY NOT a public URL: the
--    existing upload dir is served permitAll (GET /uploads/**), and a
--    patient photo is PHI, so the binary is streamed through an
--    AUTHENTICATED endpoint instead.
--  * Consent-to-treat: no such concept exists. The portal pre-check-in form
--    already FORCES patients to tick a consent checkbox and the backend
--    discarded the value (PreCheckInRequestDTO.consentAcknowledged was
--    write-only) — patients attested consent that was recorded nowhere.
--    digital_signatures cannot host this (signed_by_staff_id NOT NULL, no
--    CONSENT type), so this is a purpose-built table following the
--    advance_directives revoke-never-delete shape + the V125 digest idiom.
--  * Guarantor: zero guarantor/responsible-party modeling exists anywhere;
--    the only payer-other-than-patient data is insurance subscriber fields.
--
-- New tables carry REAL foreign keys; column adds are idempotent by rule.

ALTER TABLE clinical.patients ADD COLUMN IF NOT EXISTS photo_file_path VARCHAR(1024);
ALTER TABLE clinical.patients ADD COLUMN IF NOT EXISTS photo_content_type VARCHAR(100);
ALTER TABLE clinical.patients ADD COLUMN IF NOT EXISTS photo_updated_at TIMESTAMP;

CREATE TABLE IF NOT EXISTS clinical.patient_treatment_consents (
    id                    UUID          NOT NULL DEFAULT gen_random_uuid(),
    patient_id            UUID          NOT NULL,
    hospital_id           UUID          NOT NULL,
    appointment_id        UUID,
    encounter_id          UUID,
    status                VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    method                VARCHAR(20)   NOT NULL,
    source                VARCHAR(20)   NOT NULL,
    signed_name           VARCHAR(200),
    signature_hash        VARCHAR(64),
    consented_at          TIMESTAMP     NOT NULL DEFAULT now(),
    expires_at            TIMESTAMP,
    recorded_by_staff_id  UUID,
    recorded_by_user_id   UUID,
    revoked_at            TIMESTAMP,
    revoked_by_user_id    UUID,
    revocation_reason     VARCHAR(500),
    notes                 VARCHAR(500),
    created_at            TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_patient_treatment_consents PRIMARY KEY (id),
    CONSTRAINT fk_ptc_patient      FOREIGN KEY (patient_id)           REFERENCES clinical.patients(id),
    CONSTRAINT fk_ptc_hospital     FOREIGN KEY (hospital_id)          REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_ptc_appointment  FOREIGN KEY (appointment_id)       REFERENCES clinical.appointments(id),
    CONSTRAINT fk_ptc_encounter    FOREIGN KEY (encounter_id)         REFERENCES clinical.encounters(id),
    CONSTRAINT fk_ptc_staff        FOREIGN KEY (recorded_by_staff_id) REFERENCES hospital.staff(id) ON DELETE NO ACTION,
    CONSTRAINT fk_ptc_recorder     FOREIGN KEY (recorded_by_user_id)  REFERENCES security.users(id) ON DELETE NO ACTION,
    CONSTRAINT fk_ptc_revoker      FOREIGN KEY (revoked_by_user_id)   REFERENCES security.users(id) ON DELETE NO ACTION
);

CREATE INDEX IF NOT EXISTS idx_ptc_patient  ON clinical.patient_treatment_consents (patient_id);
CREATE INDEX IF NOT EXISTS idx_ptc_hospital ON clinical.patient_treatment_consents (hospital_id);
-- The check-in surface asks "does this patient have an active consent here?"
CREATE INDEX IF NOT EXISTS idx_ptc_active
    ON clinical.patient_treatment_consents (patient_id, hospital_id)
    WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS clinical.patient_guarantors (
    id           UUID          NOT NULL DEFAULT gen_random_uuid(),
    patient_id   UUID          NOT NULL,
    hospital_id  UUID          NOT NULL,
    full_name    VARCHAR(200)  NOT NULL,
    relationship VARCHAR(50),
    phone        VARCHAR(20),
    email        VARCHAR(150),
    address      VARCHAR(255),
    is_primary   BOOLEAN       NOT NULL DEFAULT false,
    active       BOOLEAN       NOT NULL DEFAULT true,
    notes        VARCHAR(500),
    created_at   TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_patient_guarantors PRIMARY KEY (id),
    CONSTRAINT fk_guarantor_patient  FOREIGN KEY (patient_id)  REFERENCES clinical.patients(id),
    CONSTRAINT fk_guarantor_hospital FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals(id)
);

CREATE INDEX IF NOT EXISTS idx_guarantor_patient  ON clinical.patient_guarantors (patient_id);
CREATE INDEX IF NOT EXISTS idx_guarantor_hospital ON clinical.patient_guarantors (hospital_id);
