-- V124: lab.micro_culture_results + micro_isolates + micro_susceptibilities
-- (P3 item 19, microbiology — the biggest lab-domain absence).
--
-- WHY: the lab domain has zero modeling for cultures. A LabResult is one
-- opaque result_value string per row with no analyte identity, so a culture
-- (organism identification, then a susceptibility panel PER organism) cannot
-- be represented at all — a positive blood culture entered as "Positive"
-- computes severity UNSPECIFIED and is invisible to the critical-value chain.
-- These are the first micro-shaped tables: a culture report hangs off the
-- existing lab_orders/lab_specimens workflow, isolates hang off the culture,
-- susceptibilities hang off the isolate.
--
-- Report lifecycle is PRELIMINARY -> FINAL -> CORRECTED (naming follows
-- ImagingReportStatus, the one existing prelim/final vocabulary). patient_id
-- and hospital_id are denormalized onto the culture because lab_results has
-- no hospital column and tenancy-by-join has already produced cross-tenant
-- holes (acknowledge/read-back); scoping here stays a column scan.
--
-- New tables, so they carry REAL foreign keys. Idempotent by rule (a deploy
-- killed mid-Liquibase must not wedge the environment).

CREATE TABLE IF NOT EXISTS lab.micro_culture_results (
    id                    UUID          NOT NULL DEFAULT gen_random_uuid(),
    lab_order_id          UUID          NOT NULL,
    specimen_id           UUID,
    patient_id            UUID          NOT NULL,
    hospital_id           UUID          NOT NULL,
    specimen_source       VARCHAR(100),
    collected_at          TIMESTAMP,
    status                VARCHAR(20)   NOT NULL DEFAULT 'PRELIMINARY',
    growth_result         VARCHAR(20),
    gram_stain            VARCHAR(255),
    finalized_at          TIMESTAMP,
    finalized_by_user_id  UUID,
    corrected_at          TIMESTAMP,
    correction_reason     VARCHAR(500),
    reported_by_staff_id  UUID,
    documented_by_user_id UUID,
    notes                 VARCHAR(1000),
    created_at            TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_micro_culture_results     PRIMARY KEY (id),
    CONSTRAINT fk_micro_culture_order       FOREIGN KEY (lab_order_id)          REFERENCES lab.lab_orders(id),
    CONSTRAINT fk_micro_culture_specimen    FOREIGN KEY (specimen_id)           REFERENCES lab.lab_specimens(id),
    CONSTRAINT fk_micro_culture_patient     FOREIGN KEY (patient_id)            REFERENCES clinical.patients(id),
    CONSTRAINT fk_micro_culture_hospital    FOREIGN KEY (hospital_id)           REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_micro_culture_finalizer   FOREIGN KEY (finalized_by_user_id)  REFERENCES security.users(id) ON DELETE NO ACTION,
    CONSTRAINT fk_micro_culture_staff       FOREIGN KEY (reported_by_staff_id)  REFERENCES hospital.staff(id) ON DELETE NO ACTION,
    CONSTRAINT fk_micro_culture_user        FOREIGN KEY (documented_by_user_id) REFERENCES security.users(id) ON DELETE NO ACTION
);

CREATE INDEX IF NOT EXISTS idx_micro_culture_order    ON lab.micro_culture_results (lab_order_id);
CREATE INDEX IF NOT EXISTS idx_micro_culture_patient  ON lab.micro_culture_results (patient_id);
CREATE INDEX IF NOT EXISTS idx_micro_culture_hospital ON lab.micro_culture_results (hospital_id);
CREATE INDEX IF NOT EXISTS idx_micro_culture_status   ON lab.micro_culture_results (status);

CREATE TABLE IF NOT EXISTS lab.micro_isolates (
    id                 UUID          NOT NULL DEFAULT gen_random_uuid(),
    culture_result_id  UUID          NOT NULL,
    isolate_number     INTEGER       NOT NULL DEFAULT 1,
    organism_name      VARCHAR(200)  NOT NULL,
    organism_code      VARCHAR(50),
    growth_quantity    VARCHAR(50),
    notes              VARCHAR(500),
    created_at         TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_micro_isolates       PRIMARY KEY (id),
    CONSTRAINT fk_micro_isolate_culture FOREIGN KEY (culture_result_id) REFERENCES lab.micro_culture_results(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_micro_isolate_culture ON lab.micro_isolates (culture_result_id);

CREATE TABLE IF NOT EXISTS lab.micro_susceptibilities (
    id               UUID          NOT NULL DEFAULT gen_random_uuid(),
    isolate_id       UUID          NOT NULL,
    antibiotic_name  VARCHAR(150)  NOT NULL,
    antibiotic_code  VARCHAR(50),
    method           VARCHAR(20),
    mic_value        VARCHAR(30),
    interpretation   VARCHAR(15)   NOT NULL,
    notes            VARCHAR(300),
    created_at       TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_micro_susceptibilities        PRIMARY KEY (id),
    CONSTRAINT fk_micro_susc_isolate            FOREIGN KEY (isolate_id) REFERENCES lab.micro_isolates(id) ON DELETE CASCADE,
    CONSTRAINT uq_micro_susc_isolate_antibiotic UNIQUE (isolate_id, antibiotic_name)
);

CREATE INDEX IF NOT EXISTS idx_micro_susc_isolate ON lab.micro_susceptibilities (isolate_id);
