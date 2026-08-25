-- V135: death and mortality workflow (Tier 2 item 29).
--
-- WHY: this system had no concept of a patient dying. Searches for
-- DeathRecord, dateOfDeath, deathDate and Mortality all returned nothing.
-- A patient who died stayed ACTIVE: open admissions and encounters, live
-- appointments, and — worst — the appointment-reminder and recall sweeps
-- (V112, V128) select purely on appointment/recall state with no patient
-- condition at all, so they will cheerfully text the family of someone who
-- died last week to remind them of a follow-up.
--
-- It also blocks the maternal and perinatal mortality indicators that the
-- DHIS2 ADX export exists to report. For a maternal-newborn EHR those are
-- not optional statistics; they are the numbers the facility is measured on.
--
-- TWO PIECES, and the split is deliberate:
--
--   clinical.patients.deceased_at is the FLAG everything else reads. It is
--   denormalised onto the patient precisely so the sweeps can filter with a
--   column scan instead of a join — the same reasoning that put hospital_id
--   on micro_culture_results, and joins-for-safety-checks is how the
--   cross-tenant holes in acknowledge/read-back happened.
--
--   clinical.death_records is the CERTIFICATE: when, where, by whose hand,
--   and of what. One row per patient, because a person dies once.
--
-- CAUSE OF DEATH IS AMENDABLE. An autopsy or a coroner routinely changes it
-- weeks later, so the record carries an amendment reason and timestamp
-- rather than being immutable. What is NOT amendable is the fact of death:
-- there is no un-death path, and reversing a death is a data-correction
-- exercise, not a workflow.
--
-- New table, so it carries REAL foreign keys. Idempotent by rule.

ALTER TABLE clinical.patients
    ADD COLUMN IF NOT EXISTS deceased_at TIMESTAMP WITHOUT TIME ZONE;

-- Every sweep and worklist that must skip the dead reads this predicate.
-- Partial index because the living are the overwhelming majority and the
-- queries all ask "IS NULL".
CREATE INDEX IF NOT EXISTS idx_patient_deceased
    ON clinical.patients (deceased_at)
    WHERE deceased_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS clinical.death_records (
    id                      UUID          NOT NULL DEFAULT gen_random_uuid(),
    patient_id              UUID          NOT NULL,
    hospital_id             UUID          NOT NULL,
    encounter_id            UUID,
    admission_id            UUID,

    died_at                 TIMESTAMP     NOT NULL,
    place_of_death          VARCHAR(30)   NOT NULL DEFAULT 'FACILITY',
    manner_of_death         VARCHAR(30)   NOT NULL DEFAULT 'NATURAL',

    -- Immediate cause is what finally stopped the heart; underlying cause is
    -- the disease that set the sequence going. ICD-10 mortality coding wants
    -- both, and the underlying cause is the one that counts for statistics.
    immediate_cause         VARCHAR(500)  NOT NULL,
    immediate_cause_code    VARCHAR(20),
    underlying_cause        VARCHAR(500),
    underlying_cause_code   VARCHAR(20),
    contributing_causes     VARCHAR(1000),

    -- The two flags this product exists to report on.
    maternal_death          BOOLEAN       NOT NULL DEFAULT false,
    maternal_death_timing   VARCHAR(30),
    perinatal_death         BOOLEAN       NOT NULL DEFAULT false,
    perinatal_type          VARCHAR(30),

    autopsy_requested       BOOLEAN       NOT NULL DEFAULT false,
    certified_by_staff_id   UUID,
    certified_at            TIMESTAMP,

    -- An autopsy or coroner routinely revises the cause weeks later.
    amended_at              TIMESTAMP,
    amendment_reason        VARCHAR(500),

    notes                   VARCHAR(1000),
    recorded_by_staff_id    UUID,
    created_at              TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_death_records        PRIMARY KEY (id),
    CONSTRAINT fk_death_patient        FOREIGN KEY (patient_id)            REFERENCES clinical.patients(id),
    CONSTRAINT fk_death_hospital       FOREIGN KEY (hospital_id)           REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_death_certifier      FOREIGN KEY (certified_by_staff_id) REFERENCES hospital.staff(id) ON DELETE NO ACTION,
    CONSTRAINT fk_death_recorder       FOREIGN KEY (recorded_by_staff_id)  REFERENCES hospital.staff(id) ON DELETE NO ACTION,
    CONSTRAINT ck_death_maternal_timing CHECK (maternal_death = false OR maternal_death_timing IS NOT NULL),
    CONSTRAINT ck_death_perinatal_type  CHECK (perinatal_death = false OR perinatal_type IS NOT NULL)
);

-- A person dies once. The database says so rather than the service hoping so.
CREATE UNIQUE INDEX IF NOT EXISTS uq_death_record_patient
    ON clinical.death_records (patient_id);

CREATE INDEX IF NOT EXISTS idx_death_hospital ON clinical.death_records (hospital_id, died_at);

-- The mortality register is read by period, and the maternal / perinatal
-- subsets are the DHIS2 indicator queries. Partial indexes keep those cheap.
CREATE INDEX IF NOT EXISTS idx_death_maternal
    ON clinical.death_records (hospital_id, died_at)
    WHERE maternal_death;

CREATE INDEX IF NOT EXISTS idx_death_perinatal
    ON clinical.death_records (hospital_id, died_at)
    WHERE perinatal_death;
