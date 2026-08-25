-- V134: blood bank and transfusion (Tier 2 item 28).
--
-- WHY: `bloodProductsRequired` — a single boolean on ProcedureOrder — was the
-- entire footprint of transfusion in this codebase. Searches for Transfusion,
-- BloodBank and CrossMatch returned nothing at all. Meanwhile PR #437 shipped
-- the partograph with postpartum-haemorrhage alerts, and PPH is the leading
-- cause of maternal death: the product raises the alarm and then has nowhere
-- to record the intervention that answers it.
--
-- SCOPE: the transfusion LOOP — type & screen, request, crossmatch, issue,
-- administration, reaction. Deliberately NOT donor recruitment or a managed
-- stock ledger. Units are recorded as received against a request, which fits
-- both a facility holding its own inventory and one drawing per-request from
-- an external blood bank; standing inventory is a separate decision and is not
-- being made silently here.
--
-- WHY A SEPARATE BLOOD GROUP TABLE, given clinical.patients.blood_type exists:
-- that column is free text, patient-reported, and editable from the
-- registration desk. You do not transfuse on a patient-reported blood type.
-- patient_blood_groups is the LAB-VERIFIED type and screen: coded ABO/Rh, an
-- antibody screen, a specimen, a performer, and an expiry — because an
-- antibody screen goes stale (a recently transfused or pregnant patient can
-- develop new antibodies) while ABO/Rh does not. The two are kept apart on
-- purpose; the free-text column keeps its administrative meaning.
--
-- New tables, so they carry REAL foreign keys — the V1-era tables have none
-- and every dangling-FK bug in this repo traces back to that. Idempotent by
-- rule: a deploy killed mid-Liquibase must not wedge the environment.

-- ── Type and screen ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS clinical.patient_blood_groups (
    id                     UUID         NOT NULL DEFAULT gen_random_uuid(),
    patient_id             UUID         NOT NULL,
    hospital_id            UUID         NOT NULL,
    abo_group              VARCHAR(3)   NOT NULL,
    rh_factor              VARCHAR(10)  NOT NULL,
    antibody_screen        VARCHAR(20)  NOT NULL DEFAULT 'NOT_DONE',
    antibody_detail        VARCHAR(500),
    specimen_collected_at  TIMESTAMP,
    performed_at           TIMESTAMP    NOT NULL DEFAULT now(),
    -- An antibody screen expires; ABO/Rh does not. A null expiry means the
    -- ABO/Rh stands indefinitely but the screen must be repeated before
    -- crossmatch, which the service enforces rather than the schema.
    expires_at             TIMESTAMP,
    performed_by_staff_id  UUID,
    superseded             BOOLEAN      NOT NULL DEFAULT false,
    notes                  VARCHAR(1000),
    created_at             TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at             TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_patient_blood_groups     PRIMARY KEY (id),
    CONSTRAINT fk_blood_group_patient      FOREIGN KEY (patient_id)            REFERENCES clinical.patients(id),
    CONSTRAINT fk_blood_group_hospital     FOREIGN KEY (hospital_id)           REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_blood_group_performer    FOREIGN KEY (performed_by_staff_id) REFERENCES hospital.staff(id) ON DELETE NO ACTION,
    CONSTRAINT ck_blood_group_abo          CHECK (abo_group IN ('A','B','AB','O')),
    CONSTRAINT ck_blood_group_rh           CHECK (rh_factor IN ('POSITIVE','NEGATIVE'))
);

CREATE INDEX IF NOT EXISTS idx_blood_group_patient  ON clinical.patient_blood_groups (patient_id);
CREATE INDEX IF NOT EXISTS idx_blood_group_hospital ON clinical.patient_blood_groups (hospital_id);

-- At most one CURRENT type & screen per patient per hospital. Superseding is
-- how a repeat screen lands, so history is kept rather than overwritten — the
-- deactivate-never-delete stance used for guarantors and directives.
CREATE UNIQUE INDEX IF NOT EXISTS uq_blood_group_current
    ON clinical.patient_blood_groups (patient_id, hospital_id)
    WHERE superseded = false;

-- ── Requests ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS clinical.transfusion_requests (
    id                     UUID         NOT NULL DEFAULT gen_random_uuid(),
    patient_id             UUID         NOT NULL,
    hospital_id            UUID         NOT NULL,
    encounter_id           UUID,
    blood_group_id         UUID,
    product_type           VARCHAR(30)  NOT NULL,
    units_requested        INTEGER      NOT NULL,
    indication             VARCHAR(500) NOT NULL,
    urgency                VARCHAR(20)  NOT NULL DEFAULT 'ROUTINE',
    status                 VARCHAR(20)  NOT NULL DEFAULT 'REQUESTED',
    requested_by_staff_id  UUID,
    requested_at           TIMESTAMP    NOT NULL DEFAULT now(),
    required_by            TIMESTAMP,
    cancel_reason          VARCHAR(500),
    notes                  VARCHAR(1000),
    created_at             TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at             TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_transfusion_requests     PRIMARY KEY (id),
    CONSTRAINT fk_transfusion_req_patient  FOREIGN KEY (patient_id)            REFERENCES clinical.patients(id),
    CONSTRAINT fk_transfusion_req_hospital FOREIGN KEY (hospital_id)           REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_transfusion_req_group    FOREIGN KEY (blood_group_id)        REFERENCES clinical.patient_blood_groups(id) ON DELETE NO ACTION,
    CONSTRAINT fk_transfusion_req_staff    FOREIGN KEY (requested_by_staff_id) REFERENCES hospital.staff(id) ON DELETE NO ACTION,
    CONSTRAINT ck_transfusion_req_units    CHECK (units_requested > 0)
);

CREATE INDEX IF NOT EXISTS idx_transfusion_req_patient  ON clinical.transfusion_requests (patient_id);
CREATE INDEX IF NOT EXISTS idx_transfusion_req_hospital ON clinical.transfusion_requests (hospital_id);
CREATE INDEX IF NOT EXISTS idx_transfusion_req_status   ON clinical.transfusion_requests (hospital_id, status);

-- ── Units ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS clinical.blood_units (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    hospital_id   UUID         NOT NULL,
    request_id    UUID,
    unit_number   VARCHAR(60)  NOT NULL,
    product_type  VARCHAR(30)  NOT NULL,
    abo_group     VARCHAR(3)   NOT NULL,
    rh_factor     VARCHAR(10)  NOT NULL,
    volume_ml     INTEGER,
    collected_on  DATE,
    expires_on    DATE         NOT NULL,
    source        VARCHAR(200),
    status        VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',
    discard_reason VARCHAR(500),
    notes         VARCHAR(1000),
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_blood_units          PRIMARY KEY (id),
    CONSTRAINT fk_blood_unit_hospital  FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_blood_unit_request   FOREIGN KEY (request_id)  REFERENCES clinical.transfusion_requests(id) ON DELETE NO ACTION,
    CONSTRAINT ck_blood_unit_abo       CHECK (abo_group IN ('A','B','AB','O')),
    CONSTRAINT ck_blood_unit_rh        CHECK (rh_factor IN ('POSITIVE','NEGATIVE')),
    CONSTRAINT ck_blood_unit_volume    CHECK (volume_ml IS NULL OR volume_ml > 0)
);

-- A unit number is the physical label on the bag. Two bags with one number in
-- the same facility is a wrong-unit incident waiting to happen, so the
-- database refuses it rather than the service.
CREATE UNIQUE INDEX IF NOT EXISTS uq_blood_unit_number
    ON clinical.blood_units (hospital_id, unit_number);
CREATE INDEX IF NOT EXISTS idx_blood_unit_status  ON clinical.blood_units (hospital_id, status);
CREATE INDEX IF NOT EXISTS idx_blood_unit_request ON clinical.blood_units (request_id);

-- ── Crossmatch ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS clinical.transfusion_crossmatches (
    id                      UUID         NOT NULL DEFAULT gen_random_uuid(),
    request_id              UUID         NOT NULL,
    blood_unit_id           UUID         NOT NULL,
    hospital_id             UUID         NOT NULL,
    compatible              BOOLEAN      NOT NULL,
    method                  VARCHAR(60),
    incompatibility_reason  VARCHAR(500),
    performed_by_staff_id   UUID,
    performed_at            TIMESTAMP    NOT NULL DEFAULT now(),
    expires_at              TIMESTAMP,
    created_at              TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at              TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_transfusion_crossmatches  PRIMARY KEY (id),
    CONSTRAINT fk_crossmatch_request        FOREIGN KEY (request_id)            REFERENCES clinical.transfusion_requests(id),
    CONSTRAINT fk_crossmatch_unit           FOREIGN KEY (blood_unit_id)         REFERENCES clinical.blood_units(id),
    CONSTRAINT fk_crossmatch_hospital       FOREIGN KEY (hospital_id)           REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_crossmatch_staff          FOREIGN KEY (performed_by_staff_id) REFERENCES hospital.staff(id) ON DELETE NO ACTION
);

-- One crossmatch verdict per (request, unit): re-testing the same pair
-- overwrites the verdict rather than accumulating contradictory rows.
CREATE UNIQUE INDEX IF NOT EXISTS uq_crossmatch_request_unit
    ON clinical.transfusion_crossmatches (request_id, blood_unit_id);
CREATE INDEX IF NOT EXISTS idx_crossmatch_hospital ON clinical.transfusion_crossmatches (hospital_id);

-- ── Administration ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS clinical.transfusion_administrations (
    id                      UUID         NOT NULL DEFAULT gen_random_uuid(),
    request_id              UUID         NOT NULL,
    blood_unit_id           UUID         NOT NULL,
    patient_id              UUID         NOT NULL,
    hospital_id             UUID         NOT NULL,
    status                  VARCHAR(20)  NOT NULL DEFAULT 'IN_PROGRESS',
    started_at              TIMESTAMP    NOT NULL DEFAULT now(),
    completed_at            TIMESTAMP,
    volume_transfused_ml    INTEGER,
    -- Two-person identity check at the bedside. Both are recorded because a
    -- transfusion is the one administration where a single signature is not
    -- accepted practice; the service refuses the same person for both.
    administered_by_staff_id UUID,
    verified_by_staff_id     UUID,
    verification_method     VARCHAR(60),
    stop_reason             VARCHAR(500),
    notes                   VARCHAR(1000),
    created_at              TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at              TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_transfusion_administrations PRIMARY KEY (id),
    CONSTRAINT fk_admin_request     FOREIGN KEY (request_id)               REFERENCES clinical.transfusion_requests(id),
    CONSTRAINT fk_admin_unit        FOREIGN KEY (blood_unit_id)            REFERENCES clinical.blood_units(id),
    CONSTRAINT fk_admin_patient     FOREIGN KEY (patient_id)               REFERENCES clinical.patients(id),
    CONSTRAINT fk_admin_hospital    FOREIGN KEY (hospital_id)              REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_admin_giver       FOREIGN KEY (administered_by_staff_id) REFERENCES hospital.staff(id) ON DELETE NO ACTION,
    CONSTRAINT fk_admin_verifier    FOREIGN KEY (verified_by_staff_id)     REFERENCES hospital.staff(id) ON DELETE NO ACTION,
    CONSTRAINT ck_admin_volume      CHECK (volume_transfused_ml IS NULL OR volume_transfused_ml > 0)
);

-- A unit is transfused once. This is the constraint that makes a double-hang
-- of the same bag unrepresentable rather than merely discouraged.
CREATE UNIQUE INDEX IF NOT EXISTS uq_admin_unit
    ON clinical.transfusion_administrations (blood_unit_id);
CREATE INDEX IF NOT EXISTS idx_admin_patient  ON clinical.transfusion_administrations (patient_id);
CREATE INDEX IF NOT EXISTS idx_admin_hospital ON clinical.transfusion_administrations (hospital_id, status);

-- ── Reactions ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS clinical.transfusion_reactions (
    id                   UUID         NOT NULL DEFAULT gen_random_uuid(),
    administration_id    UUID         NOT NULL,
    patient_id           UUID         NOT NULL,
    hospital_id          UUID         NOT NULL,
    reaction_type        VARCHAR(40)  NOT NULL,
    severity             VARCHAR(20)  NOT NULL,
    onset_at             TIMESTAMP    NOT NULL,
    signs_symptoms       VARCHAR(1000) NOT NULL,
    actions_taken        VARCHAR(1000),
    unit_returned_to_lab BOOLEAN      NOT NULL DEFAULT false,
    reported_by_staff_id UUID,
    reported_at          TIMESTAMP    NOT NULL DEFAULT now(),
    created_at           TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_transfusion_reactions   PRIMARY KEY (id),
    CONSTRAINT fk_reaction_administration FOREIGN KEY (administration_id)   REFERENCES clinical.transfusion_administrations(id),
    CONSTRAINT fk_reaction_patient        FOREIGN KEY (patient_id)          REFERENCES clinical.patients(id),
    CONSTRAINT fk_reaction_hospital       FOREIGN KEY (hospital_id)         REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_reaction_reporter       FOREIGN KEY (reported_by_staff_id) REFERENCES hospital.staff(id) ON DELETE NO ACTION
);

CREATE INDEX IF NOT EXISTS idx_reaction_administration ON clinical.transfusion_reactions (administration_id);
CREATE INDEX IF NOT EXISTS idx_reaction_patient        ON clinical.transfusion_reactions (patient_id);
CREATE INDEX IF NOT EXISTS idx_reaction_hospital       ON clinical.transfusion_reactions (hospital_id);
