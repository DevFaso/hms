-- V381 (owner decision 2026-11-26, option B of the report « Incompatible blood release authority »): a release of
-- red cells despite an incompatible crossmatch keeps a named physician as its normal authoriser, and gains a
-- fallback the hospital switches on in advance for the case where no physician can be reached.
--
-- 1. hospital.o_positive_release_protocols (V380): the same row of emergency release settings of a hospital gains
--    the fallback switch (off until the laboratory director turns it on) and who may declare that no physician
--    can be reached. The countersignature deadline is the one already there (review_deadline_hours).
-- 2. clinical.incompatible_release_decisions: one row per bag released despite an incompatible crossmatch,
--    beside its clinical.emergency_blood_releases row (V266): on whose authority, the antibody status then, the
--    bag chosen and why, whether the patient was informed, the countersignature and the monitoring afterwards.
--    The narratives are encrypted by the converter of the entity, hence TEXT.
-- 3. clinical.emergency_blood_releases (V266, V380): a fallback release names nobody as authorising it (its
--    decision row says the authority is owed and holds the rule in its own constraints).
-- Releases before this table have no row: there is no decision to backfill.
-- Plain statements, no DO block. Idempotent.

ALTER TABLE hospital.o_positive_release_protocols
    ADD COLUMN IF NOT EXISTS incompatible_fallback_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE hospital.o_positive_release_protocols
    ADD COLUMN IF NOT EXISTS incompatible_fallback_declarer_roles VARCHAR(200) NOT NULL
        DEFAULT 'ROLE_LAB_SCIENTIST,ROLE_LAB_TECHNICIAN,ROLE_LAB_MANAGER,ROLE_LAB_DIRECTOR';
ALTER TABLE hospital.o_positive_release_protocols DROP CONSTRAINT IF EXISTS ck_o_positive_protocol_declarer_roles;
ALTER TABLE hospital.o_positive_release_protocols ADD CONSTRAINT ck_o_positive_protocol_declarer_roles
    CHECK (length(trim(incompatible_fallback_declarer_roles)) > 0);

CREATE TABLE IF NOT EXISTS clinical.incompatible_release_decisions (
    id                             UUID         NOT NULL DEFAULT gen_random_uuid(),
    release_id                     UUID         NOT NULL,
    request_id                     UUID         NOT NULL,
    blood_unit_id                  UUID         NOT NULL,
    patient_id                     UUID         NOT NULL,
    hospital_id                    UUID         NOT NULL,
    decision_basis                 VARCHAR(30)  NOT NULL,
    fallback_enabled               BOOLEAN      NOT NULL,
    countersign_deadline_hours     INTEGER      NOT NULL,
    issued_by_user_id              UUID         NOT NULL,
    authoriser_user_id             UUID,
    declarer_role                  VARCHAR(40),
    unreachable_reason             TEXT,
    antibody_status                VARCHAR(30)  NOT NULL,
    antibody_detail                TEXT,
    crossmatch_finding             TEXT,
    recipient_abo                  VARCHAR(5),
    recipient_rh                   VARCHAR(10),
    unit_abo                       VARCHAR(5)   NOT NULL,
    unit_rh                        VARCHAR(10),
    abo_compatible                 BOOLEAN      NOT NULL,
    d_matched                      BOOLEAN      NOT NULL,
    rh_kell_matched                BOOLEAN,
    antigen_negative               BOOLEAN,
    least_incompatible             BOOLEAN      NOT NULL,
    candidates_considered          INTEGER      NOT NULL,
    unit_choice_reason             TEXT         NOT NULL,
    patient_informed               VARCHAR(20)  NOT NULL,
    patient_informed_at            TIMESTAMP,
    patient_informed_by_user_id    UUID,
    issued_at                      TIMESTAMP    NOT NULL,
    countersign_due_at             TIMESTAMP,
    countersigned_by_user_id       UUID,
    countersigned_at               TIMESTAMP,
    countersign_escalated_at       TIMESTAMP,
    notices_filed_at               TIMESTAMP,
    monitoring_outcome             VARCHAR(20),
    monitoring_checks              VARCHAR(300),
    monitoring_note                TEXT,
    monitoring_recorded_by_user_id UUID,
    monitoring_recorded_at         TIMESTAMP,
    created_at                     TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at                     TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_incompatible_release_decisions    PRIMARY KEY (id),
    CONSTRAINT uq_incompatible_decision_release     UNIQUE (release_id),
    CONSTRAINT fk_incompatible_decision_release     FOREIGN KEY (release_id)    REFERENCES clinical.emergency_blood_releases(id),
    CONSTRAINT fk_incompatible_decision_request     FOREIGN KEY (request_id)    REFERENCES clinical.transfusion_requests(id),
    CONSTRAINT fk_incompatible_decision_unit        FOREIGN KEY (blood_unit_id) REFERENCES clinical.blood_units(id),
    CONSTRAINT fk_incompatible_decision_patient     FOREIGN KEY (patient_id)    REFERENCES clinical.patients(id),
    CONSTRAINT fk_incompatible_decision_hospital    FOREIGN KEY (hospital_id)   REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_incompatible_decision_issuer      FOREIGN KEY (issued_by_user_id)              REFERENCES security.users(id) ON DELETE NO ACTION,
    CONSTRAINT fk_incompatible_decision_authoriser  FOREIGN KEY (authoriser_user_id)             REFERENCES security.users(id) ON DELETE NO ACTION,
    CONSTRAINT fk_incompatible_decision_countersign FOREIGN KEY (countersigned_by_user_id)       REFERENCES security.users(id) ON DELETE NO ACTION,
    CONSTRAINT fk_incompatible_decision_informer    FOREIGN KEY (patient_informed_by_user_id)    REFERENCES security.users(id) ON DELETE NO ACTION,
    CONSTRAINT fk_incompatible_decision_monitor     FOREIGN KEY (monitoring_recorded_by_user_id) REFERENCES security.users(id) ON DELETE NO ACTION,
    CONSTRAINT ck_incompatible_decision_basis       CHECK (decision_basis IN ('NAMED_PHYSICIAN', 'AUTHORITY_PENDING')),
    CONSTRAINT ck_incompatible_decision_antibody    CHECK (antibody_status IN ('ANTIBODY_ON_RECORD', 'NO_ANTIBODY_ON_RECORD')),
    CONSTRAINT ck_incompatible_decision_informed    CHECK (patient_informed IN ('PATIENT_INFORMED', 'FAMILY_INFORMED', 'NOT_POSSIBLE')),
    CONSTRAINT ck_incompatible_decision_outcome     CHECK (monitoring_outcome IS NULL
        OR monitoring_outcome IN ('NO_REACTION', 'REACTION_REPORTED', 'NOT_TRANSFUSED')),
    -- The never event: no row, on any path, for a bag the ABO group of the patient excludes.
    CONSTRAINT ck_incompatible_decision_abo         CHECK (abo_compatible = TRUE),
    -- The normal path names a physician or the laboratory director, who is never the person issuing.
    CONSTRAINT ck_incompatible_decision_physician   CHECK (decision_basis <> 'NAMED_PHYSICIAN'
        OR (authoriser_user_id IS NOT NULL AND authoriser_user_id <> issued_by_user_id AND countersign_due_at IS NULL)),
    -- The fallback: only where the hospital switched it on, with the declaration, its reason, the role it was
    -- made under, the least incompatible bag, and a countersignature owed. It names nobody as authorising.
    CONSTRAINT ck_incompatible_decision_fallback    CHECK (decision_basis <> 'AUTHORITY_PENDING'
        OR (fallback_enabled = TRUE AND authoriser_user_id IS NULL AND declarer_role IS NOT NULL
            AND unreachable_reason IS NOT NULL AND least_incompatible = TRUE AND countersign_due_at IS NOT NULL)),
    -- A countersignature is somebody else than the issuer, and has its time.
    CONSTRAINT ck_incompatible_decision_countersign CHECK (countersigned_by_user_id IS NULL
        OR (countersigned_at IS NOT NULL AND countersigned_by_user_id <> issued_by_user_id))
);

CREATE INDEX IF NOT EXISTS idx_incompatible_decision_hospital
    ON clinical.incompatible_release_decisions (hospital_id, issued_at);
CREATE INDEX IF NOT EXISTS idx_incompatible_decision_patient
    ON clinical.incompatible_release_decisions (patient_id);
CREATE INDEX IF NOT EXISTS idx_incompatible_decision_unit_open
    ON clinical.incompatible_release_decisions (blood_unit_id)
    WHERE monitoring_outcome IS NULL;
-- The notices of a release are owed until notices_filed_at is set: the sweep reads the rows still owed.
CREATE INDEX IF NOT EXISTS idx_incompatible_decision_notices_owed
    ON clinical.incompatible_release_decisions (issued_at)
    WHERE notices_filed_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_incompatible_decision_countersign_due
    ON clinical.incompatible_release_decisions (countersign_due_at)
    WHERE countersigned_at IS NULL AND countersign_escalated_at IS NULL;

-- 3. A fallback release names nobody as authorising it. V380 let an O positive decision do so; the rule for an
--    incompatible release (a named physician, or the fallback where the hospital enabled it) is held by the
--    constraints of clinical.incompatible_release_decisions above. A documented release still states its reason.
ALTER TABLE clinical.emergency_blood_releases DROP CONSTRAINT IF EXISTS ck_emergency_release_documented;
ALTER TABLE clinical.emergency_blood_releases ADD CONSTRAINT ck_emergency_release_documented
    CHECK (release_type = 'UNCROSSMATCHED' OR reason IS NOT NULL);
