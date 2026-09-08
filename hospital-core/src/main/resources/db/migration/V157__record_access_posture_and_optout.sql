-- V157: E8 #48 / #52 — per-hospital record-access posture and patient opt-out.
--
-- WHY: the user decided (2026-09-07) to gate cross-hospital chart access on the
-- treatment relationship rather than on a patient-signed, per-facility consent —
-- the model Epic runs in a shared instance. Two of the seven items in that
-- decision are pure data, and they have to exist BEFORE the read filter widens
-- (E8 #49), because both are honoured inside the access predicate itself:
--
--   * hospital.hospitals.record_access_posture — TREATMENT_PRESUMED (the
--     decision) or EXPLICIT_CONSENT. This is the legal escape hatch: if counsel
--     or the CIL rule that treatment-purpose access without patient
--     authorisation is not lawful here, an affected hospital flips one setting
--     and falls back to the existing consent-granted path. It sits beside
--     isolation_mode (V97) deliberately: both are per-tenant access posture.
--
--   * clinical.patient_record_sharing_optouts — a patient's decision to keep
--     their record out of cross-hospital reads. One row per episode; revoked_at
--     NULL means in force. Revocation is a stamp, never a delete, so the
--     disclosure report can show the period of exclusion and who ended it.
--
-- DEFAULT: TREATMENT_PRESUMED, per the product owner's instruction. Nothing
-- consumes it yet (the resolver ships in the same PR, the read filter in #49),
-- so the default changes no behaviour on this deploy.

ALTER TABLE hospital.hospitals
    ADD COLUMN IF NOT EXISTS record_access_posture VARCHAR(32) NOT NULL
        DEFAULT 'TREATMENT_PRESUMED';

CREATE TABLE IF NOT EXISTS clinical.patient_record_sharing_optouts (
    id                  UUID PRIMARY KEY,
    patient_id          UUID NOT NULL,
    opted_out_at        TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    reason              VARCHAR(1000),
    recorded_by_user_id UUID,
    revoked_at          TIMESTAMP WITHOUT TIME ZONE,
    revoked_by_user_id  UUID,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT fk_prso_patient FOREIGN KEY (patient_id)
        REFERENCES clinical.patients (id)
);

-- (patient_id, revoked_at) is what the predicate asks: "is there a row for this
-- patient with revoked_at IS NULL?" — one index answers it.
CREATE INDEX IF NOT EXISTS idx_prso_patient_active
    ON clinical.patient_record_sharing_optouts (patient_id, revoked_at);
