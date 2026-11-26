-- V380 (owner decision 2026-11-25, report « Emergency O positive release authority »): who decides to issue
-- O RhD positive red cells in an emergency, and what follows each release.
--
-- 1. hospital.o_positive_release_protocols: one row per hospital that wrote its emergency protocol. No row, or
--    active = FALSE, means no protocol: every O positive release is then handled as the high-risk case.
-- 2. clinical.o_positive_release_decisions: one row per O positive red-cell bag released in an emergency, beside
--    its clinical.emergency_blood_releases row (V266). It keeps the risk tier applied, the settings in force,
--    the sex and age of the patient then, who decided, the verbal order and its countersignature, the review by
--    the biologist and the follow-up of an RhD positive exposure. review_note and follow_up_note are encrypted
--    by the converter of the entity, hence TEXT.
-- 3. clinical.emergency_blood_releases (V266): the authoriser of an O positive decision may be owed.
-- Releases before this table have no row: there is no decision to backfill.
-- Plain statements, no DO block. Idempotent.

CREATE TABLE IF NOT EXISTS hospital.o_positive_release_protocols (
    id                     UUID        NOT NULL DEFAULT gen_random_uuid(),
    hospital_id            UUID        NOT NULL,
    active                 BOOLEAN     NOT NULL DEFAULT FALSE,
    female_age_cutoff      INTEGER     NOT NULL DEFAULT 50,
    boys_low_risk          BOOLEAN     NOT NULL DEFAULT FALSE,
    review_deadline_hours  INTEGER     NOT NULL DEFAULT 24,
    updated_by_user_id     UUID,
    created_at             TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMP   NOT NULL DEFAULT now(),

    CONSTRAINT pk_o_positive_release_protocols   PRIMARY KEY (id),
    CONSTRAINT uq_o_positive_protocol_hospital   UNIQUE (hospital_id),
    CONSTRAINT fk_o_positive_protocol_hospital   FOREIGN KEY (hospital_id)        REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_o_positive_protocol_updater    FOREIGN KEY (updated_by_user_id) REFERENCES security.users(id) ON DELETE NO ACTION,
    CONSTRAINT ck_o_positive_protocol_cutoff     CHECK (female_age_cutoff BETWEEN 40 AND 60),
    CONSTRAINT ck_o_positive_protocol_deadline   CHECK (review_deadline_hours BETWEEN 1 AND 72)
);

CREATE TABLE IF NOT EXISTS clinical.o_positive_release_decisions (
    id                          UUID         NOT NULL DEFAULT gen_random_uuid(),
    release_id                  UUID         NOT NULL,
    request_id                  UUID         NOT NULL,
    blood_unit_id               UUID         NOT NULL,
    patient_id                  UUID         NOT NULL,
    hospital_id                 UUID         NOT NULL,
    risk_tier                   VARCHAR(20)  NOT NULL,
    tier_reason                 VARCHAR(40)  NOT NULL,
    protocol_active             BOOLEAN      NOT NULL,
    female_age_cutoff           INTEGER      NOT NULL,
    boys_low_risk               BOOLEAN      NOT NULL,
    review_deadline_hours       INTEGER      NOT NULL,
    patient_sex                 VARCHAR(10)  NOT NULL,
    patient_age_years           INTEGER,
    patient_rh                  VARCHAR(10),
    o_negative_status           VARCHAR(30)  NOT NULL,
    decision_basis              VARCHAR(30)  NOT NULL,
    issued_by_user_id           UUID,
    decided_by_user_id          UUID,
    biologist_user_id           UUID,
    verbal_order                BOOLEAN      NOT NULL DEFAULT FALSE,
    issued_at                   TIMESTAMP    NOT NULL,
    countersign_due_at          TIMESTAMP,
    countersigned_by_user_id    UUID,
    countersigned_at            TIMESTAMP,
    countersign_escalated_at    TIMESTAMP,
    review_priority             VARCHAR(12)  NOT NULL,
    review_due_at               TIMESTAMP    NOT NULL,
    reviewed_by_user_id         UUID,
    reviewed_at                 TIMESTAMP,
    review_note                 TEXT,
    notices_filed_at            TIMESTAMP,
    four_eyes_waived            BOOLEAN      NOT NULL DEFAULT FALSE,
    disposition                 VARCHAR(20),
    disposition_at              TIMESTAMP,
    rhd_exposure                BOOLEAN      NOT NULL DEFAULT FALSE,
    follow_up_required          BOOLEAN      NOT NULL DEFAULT FALSE,
    antibody_screen_due_from    DATE,
    antibody_screen_due_by      DATE,
    antibody_screen_done_at     TIMESTAMP,
    anti_d_decision             VARCHAR(30),
    anti_d_decided_by_user_id   UUID,
    anti_d_decided_at           TIMESTAMP,
    patient_informed_by_user_id UUID,
    patient_informed_at         TIMESTAMP,
    follow_up_note              TEXT,
    created_at                  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_o_positive_release_decisions     PRIMARY KEY (id),
    CONSTRAINT uq_o_positive_decision_release      UNIQUE (release_id),
    CONSTRAINT fk_o_positive_decision_release      FOREIGN KEY (release_id)    REFERENCES clinical.emergency_blood_releases(id),
    CONSTRAINT fk_o_positive_decision_request      FOREIGN KEY (request_id)    REFERENCES clinical.transfusion_requests(id),
    CONSTRAINT fk_o_positive_decision_unit         FOREIGN KEY (blood_unit_id) REFERENCES clinical.blood_units(id),
    CONSTRAINT fk_o_positive_decision_patient      FOREIGN KEY (patient_id)    REFERENCES clinical.patients(id),
    CONSTRAINT fk_o_positive_decision_hospital     FOREIGN KEY (hospital_id)   REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_o_positive_decision_issuer       FOREIGN KEY (issued_by_user_id)           REFERENCES security.users(id) ON DELETE NO ACTION,
    CONSTRAINT fk_o_positive_decision_decider      FOREIGN KEY (decided_by_user_id)          REFERENCES security.users(id) ON DELETE NO ACTION,
    CONSTRAINT fk_o_positive_decision_biologist    FOREIGN KEY (biologist_user_id)           REFERENCES security.users(id) ON DELETE NO ACTION,
    CONSTRAINT fk_o_positive_decision_countersign  FOREIGN KEY (countersigned_by_user_id)    REFERENCES security.users(id) ON DELETE NO ACTION,
    CONSTRAINT fk_o_positive_decision_reviewer     FOREIGN KEY (reviewed_by_user_id)         REFERENCES security.users(id) ON DELETE NO ACTION,
    CONSTRAINT fk_o_positive_decision_anti_d       FOREIGN KEY (anti_d_decided_by_user_id)   REFERENCES security.users(id) ON DELETE NO ACTION,
    CONSTRAINT fk_o_positive_decision_informer     FOREIGN KEY (patient_informed_by_user_id) REFERENCES security.users(id) ON DELETE NO ACTION,
    CONSTRAINT ck_o_positive_decision_tier         CHECK (risk_tier IN ('LOW_RISK', 'HIGH_RISK', 'KNOWN_ANTIBODY')),
    CONSTRAINT ck_o_positive_decision_basis        CHECK (decision_basis IN ('PROTOCOL', 'PHYSICIAN_ORDER', 'BIOLOGIST_AUTHORITY', 'PHYSICIAN_AND_BIOLOGIST', 'AUTHORITY_PENDING')),
    CONSTRAINT ck_o_positive_decision_priority     CHECK (review_priority IN ('ROUTINE', 'URGENT', 'IMMEDIATE')),
    CONSTRAINT ck_o_positive_decision_sex          CHECK (patient_sex IN ('MALE', 'FEMALE', 'UNKNOWN')),
    -- What became of the bag of THIS release; null while it is out.
    CONSTRAINT ck_o_positive_decision_disposition  CHECK (disposition IS NULL
        OR disposition IN ('ADMINISTERED', 'RETURNED_UNUSED', 'SUPERSEDED')),
    CONSTRAINT ck_o_positive_decision_o_negative   CHECK (o_negative_status IN ('OUT_OF_STOCK', 'STATED_UNOBTAINABLE', 'PROTOCOL_FIRST_LINE')),
    -- The issuer alone decides only in the low-risk tier, under the active protocol of the hospital.
    CONSTRAINT ck_o_positive_decision_issuer_alone CHECK (decision_basis <> 'PROTOCOL'
        OR (risk_tier = 'LOW_RISK' AND protocol_active = TRUE)),
    -- An order by a physician, or the authority of the biologist, names that person, who is never the person issuing.
    CONSTRAINT ck_o_positive_decision_physician    CHECK (decision_basis NOT IN ('PHYSICIAN_ORDER', 'BIOLOGIST_AUTHORITY', 'PHYSICIAN_AND_BIOLOGIST')
        OR (decided_by_user_id IS NOT NULL AND decided_by_user_id IS DISTINCT FROM issued_by_user_id)),
    CONSTRAINT ck_o_positive_decision_biologist    CHECK (decision_basis <> 'PHYSICIAN_AND_BIOLOGIST' OR biologist_user_id IS NOT NULL),
    -- Outside the low-risk tier a countersignature is always owed.
    CONSTRAINT ck_o_positive_decision_countersign  CHECK (risk_tier = 'LOW_RISK' OR countersign_due_at IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_o_positive_decision_hospital
    ON clinical.o_positive_release_decisions (hospital_id, issued_at);
CREATE INDEX IF NOT EXISTS idx_o_positive_decision_patient
    ON clinical.o_positive_release_decisions (patient_id, rhd_exposure);
-- The notices of a release are owed until notices_filed_at is set: the sweep reads the rows still owed.
CREATE INDEX IF NOT EXISTS idx_o_positive_decision_notices_owed
    ON clinical.o_positive_release_decisions (issued_at)
    WHERE notices_filed_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_o_positive_decision_unit_out
    ON clinical.o_positive_release_decisions (blood_unit_id)
    WHERE disposition IS NULL;
CREATE INDEX IF NOT EXISTS idx_o_positive_decision_countersign_due
    ON clinical.o_positive_release_decisions (countersign_due_at)
    WHERE countersigned_at IS NULL AND countersign_escalated_at IS NULL;

-- 3. An O positive decision released with no physician reachable names nobody as authorising it (the decision row
--    says the authority is owed): V266 required a name for every documented release. A serologically incompatible
--    release still requires one.
ALTER TABLE clinical.emergency_blood_releases DROP CONSTRAINT IF EXISTS ck_emergency_release_documented;
ALTER TABLE clinical.emergency_blood_releases ADD CONSTRAINT ck_emergency_release_documented
    CHECK (release_type = 'UNCROSSMATCHED'
        OR (reason IS NOT NULL AND (authoriser_user_id IS NOT NULL OR release_type = 'O_POSITIVE_DECISION')));
