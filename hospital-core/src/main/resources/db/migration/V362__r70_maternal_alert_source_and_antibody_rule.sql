-- V362 (QA r70: D3304, D3351). Two additive changes, plain statements, no DO block, idempotent.

-- 1. QA D3304: a maternal early-warning page (medium, high or a RED add-on alert) raised from a ward vital
--    set is filed as an urgent obstetric alert to acknowledge, with source_type 'VITAL_SIGN'. The V292 CHECK
--    did not allow it. Widen the CHECK to every source ObstetricAlertEscalationService can write
--    (ObstetricAlertEscalationSourceTypeGuardTest keeps the two in step). Drop if it exists, then re-add.

ALTER TABLE clinical.obstetric_alert_escalations
    DROP CONSTRAINT IF EXISTS chk_oae_source_type;

ALTER TABLE clinical.obstetric_alert_escalations
    ADD CONSTRAINT chk_oae_source_type CHECK (source_type IN
        ('PARTOGRAPH_ENTRY', 'DELIVERY', 'NEWBORN_ASSESSMENT', 'POSTPARTUM_OBSERVATION', 'LABOR_TRANSFER',
         'VITAL_SIGN'));

-- 2. QA D3351, provisional rule TRANSFUSION_POSITIVE_ANTIBODY_SCREEN (research report « Transfusion and
--    critical result cover », section 1). A positive antibody screen makes the patient allo-immunised for good;
--    routine red cells then leave the laboratory only for an identified antibody (or a biologist's decision on a
--    named referral), on a unit attested antigen-negative and RH-KEL1 matched, with a compatible antiglobulin
--    crossmatch on a sample of 72 hours or less.

-- 2a. What the biologist recorded: the antibody identified, or the referral and the decision to serve one
--     request before the answer. Append-only: a row is never updated. specificities, decision_reason and notes
--     are clinical narratives, encrypted by the entity's converter, hence TEXT.
CREATE TABLE IF NOT EXISTS clinical.patient_antibody_workups (
    id                      UUID         NOT NULL DEFAULT gen_random_uuid(),
    patient_id              UUID         NOT NULL,
    hospital_id             UUID         NOT NULL,
    status                  VARCHAR(20)  NOT NULL,
    antibody_specificities  TEXT,
    referral_centre         VARCHAR(200),
    decision_reason         TEXT,
    transfusion_request_id  UUID,
    recorded_by_user_id     UUID         NOT NULL,
    recorded_by_display     VARCHAR(200),
    recorded_at             TIMESTAMP    NOT NULL,
    notes                   TEXT,
    created_at              TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at              TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_patient_antibody_workups      PRIMARY KEY (id),
    CONSTRAINT fk_antibody_workup_patient       FOREIGN KEY (patient_id)             REFERENCES clinical.patients(id),
    CONSTRAINT fk_antibody_workup_hospital      FOREIGN KEY (hospital_id)            REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_antibody_workup_request       FOREIGN KEY (transfusion_request_id) REFERENCES clinical.transfusion_requests(id),
    CONSTRAINT fk_antibody_workup_recorder      FOREIGN KEY (recorded_by_user_id)    REFERENCES security.users(id) ON DELETE NO ACTION,
    CONSTRAINT ck_antibody_workup_status        CHECK (status IN ('IDENTIFIED', 'REFERRED')),
    CONSTRAINT ck_antibody_workup_identified    CHECK (status <> 'IDENTIFIED' OR antibody_specificities IS NOT NULL),
    CONSTRAINT ck_antibody_workup_referred      CHECK (status <> 'REFERRED'
        OR (referral_centre IS NOT NULL AND decision_reason IS NOT NULL AND transfusion_request_id IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_antibody_workup_patient
    ON clinical.patient_antibody_workups (patient_id, hospital_id, recorded_at);

-- 2b. What makes a crossmatch « compatibilisé » for an allo-immunised patient: the sample it was done on, the
--     antiglobulin technique, and the two attestations about the unit. NULL on every earlier row and for a
--     patient with no antibody.
ALTER TABLE clinical.transfusion_crossmatches ADD COLUMN IF NOT EXISTS sample_collected_at TIMESTAMP;
ALTER TABLE clinical.transfusion_crossmatches ADD COLUMN IF NOT EXISTS antiglobulin_method BOOLEAN;
ALTER TABLE clinical.transfusion_crossmatches ADD COLUMN IF NOT EXISTS antigen_negative BOOLEAN;
ALTER TABLE clinical.transfusion_crossmatches ADD COLUMN IF NOT EXISTS rh_kell_matched BOOLEAN;

-- 2c. The prescriber's declaration of a life-threatening emergency (UVI or UV), its reason, and that the
--     pre-transfusion sample is drawn before the first unit. NULL on an ordinary request.
ALTER TABLE clinical.transfusion_requests ADD COLUMN IF NOT EXISTS vital_urgency_level VARCHAR(10);
ALTER TABLE clinical.transfusion_requests ADD COLUMN IF NOT EXISTS vital_urgency_reason TEXT;
ALTER TABLE clinical.transfusion_requests ADD COLUMN IF NOT EXISTS pre_transfusion_sample_drawn BOOLEAN;
