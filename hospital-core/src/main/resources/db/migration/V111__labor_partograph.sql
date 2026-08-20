-- ─────────────────────────────────────────────────────────────────────────────
-- P1 #6 (roadmap row 41, "antepartum / partogram" surface): Labor & Delivery.
--
--   clinical.labor_episodes              — intrapartum episode aggregate; its
--                                          outcome column is the "Pregnancy
--                                          .outcome" the OB scope audit
--                                          deferred for lack of a Pregnancy
--                                          entity.
--   clinical.labor_partograph_entries    — WHO partograph timepoints
--                                          (+ alert collection table).
--   clinical.delivery_records            — birth-event facts, one per episode
--                                          (+ alert collection table).
--   clinical.newborn_assessments         — gains delivery_record_id back-link
--                                          (nullable: pre-module and
--                                          transferred-in infants).
--
-- IF NOT EXISTS everywhere (house rule since the V108/V110 incident: a deploy
-- killed mid-Liquibase must be able to re-run this changeset safely).
-- Forward-only; no rollback script.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS clinical.labor_episodes (
    id                      UUID          NOT NULL DEFAULT gen_random_uuid(),
    patient_id              UUID          NOT NULL,
    hospital_id             UUID          NOT NULL,
    registration_id         UUID,
    maternal_history_id     UUID,
    admitted_by_staff_id    UUID,
    documented_by_user_id   UUID,
    labor_onset_at          TIMESTAMP,
    admitted_at             TIMESTAMP     NOT NULL DEFAULT now(),
    membrane_status         VARCHAR(30),
    membrane_rupture_at     TIMESTAMP,
    gestational_age_weeks   INTEGER,
    gravida                 INTEGER,
    para                    INTEGER,
    active_phase_start_at   TIMESTAMP,
    status                  VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    outcome                 VARCHAR(30),
    risk_notes              TEXT,
    created_at              TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_labor_episodes                 PRIMARY KEY (id),
    CONSTRAINT fk_labor_episode_patient          FOREIGN KEY (patient_id)            REFERENCES clinical.patients(id),
    CONSTRAINT fk_labor_episode_hospital         FOREIGN KEY (hospital_id)           REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_labor_episode_registration     FOREIGN KEY (registration_id)       REFERENCES clinical.patient_hospital_registrations(id) ON DELETE NO ACTION,
    CONSTRAINT fk_labor_episode_maternal_history FOREIGN KEY (maternal_history_id)   REFERENCES clinical.maternal_history(id) ON DELETE NO ACTION,
    CONSTRAINT fk_labor_episode_staff            FOREIGN KEY (admitted_by_staff_id)  REFERENCES hospital.staff(id) ON DELETE NO ACTION,
    CONSTRAINT fk_labor_episode_user             FOREIGN KEY (documented_by_user_id) REFERENCES security.users(id) ON DELETE NO ACTION
);

CREATE INDEX IF NOT EXISTS idx_labor_episode_patient  ON clinical.labor_episodes (patient_id);
CREATE INDEX IF NOT EXISTS idx_labor_episode_hospital ON clinical.labor_episodes (hospital_id);
CREATE INDEX IF NOT EXISTS idx_labor_episode_status   ON clinical.labor_episodes (status);

CREATE TABLE IF NOT EXISTS clinical.labor_partograph_entries (
    id                             UUID          NOT NULL DEFAULT gen_random_uuid(),
    episode_id                     UUID          NOT NULL,
    patient_id                     UUID          NOT NULL,
    hospital_id                    UUID          NOT NULL,
    recorded_by_staff_id           UUID,
    documented_by_user_id          UUID,
    observation_time               TIMESTAMP     NOT NULL,
    documented_at                  TIMESTAMP     NOT NULL DEFAULT now(),
    late_entry                     BOOLEAN       NOT NULL DEFAULT false,
    original_entry_time            TIMESTAMP,
    fetal_heart_rate_bpm           INTEGER,
    liquor_colour                  VARCHAR(30),
    moulding_degree                VARCHAR(20),
    cervical_dilation_cm           INTEGER,
    descent_fifths                 INTEGER,
    contractions_per_ten_minutes   INTEGER,
    contraction_duration_seconds   INTEGER,
    oxytocin_drops_per_minute      INTEGER,
    drugs_given                    TEXT,
    iv_fluids                      TEXT,
    pulse_bpm                      INTEGER,
    systolic_bp_mm_hg              INTEGER,
    diastolic_bp_mm_hg             INTEGER,
    temperature_celsius            DOUBLE PRECISION,
    urine_output_ml                INTEGER,
    urine_protein                  VARCHAR(10),
    urine_acetone                  VARCHAR(10),
    notes                          TEXT,
    created_at                     TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at                     TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_labor_partograph_entries   PRIMARY KEY (id),
    CONSTRAINT fk_partograph_entry_episode   FOREIGN KEY (episode_id)            REFERENCES clinical.labor_episodes(id),
    CONSTRAINT fk_partograph_entry_patient   FOREIGN KEY (patient_id)            REFERENCES clinical.patients(id),
    CONSTRAINT fk_partograph_entry_hospital  FOREIGN KEY (hospital_id)           REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_partograph_entry_staff     FOREIGN KEY (recorded_by_staff_id)  REFERENCES hospital.staff(id) ON DELETE NO ACTION,
    CONSTRAINT fk_partograph_entry_user      FOREIGN KEY (documented_by_user_id) REFERENCES security.users(id) ON DELETE NO ACTION
);

CREATE INDEX IF NOT EXISTS idx_partograph_entry_episode  ON clinical.labor_partograph_entries (episode_id);
CREATE INDEX IF NOT EXISTS idx_partograph_entry_patient  ON clinical.labor_partograph_entries (patient_id);
CREATE INDEX IF NOT EXISTS idx_partograph_entry_hospital ON clinical.labor_partograph_entries (hospital_id);
CREATE INDEX IF NOT EXISTS idx_partograph_entry_time     ON clinical.labor_partograph_entries (observation_time);

CREATE TABLE IF NOT EXISTS clinical.labor_partograph_entry_alerts (
    entry_id        UUID          NOT NULL,
    alert_order     INTEGER       NOT NULL,
    alert_type      VARCHAR(32)   NOT NULL,
    alert_severity  VARCHAR(16)   NOT NULL,
    alert_code      VARCHAR(64),
    alert_message   TEXT          NOT NULL,
    triggered_by    VARCHAR(120),
    created_at      TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_labor_partograph_entry_alerts PRIMARY KEY (entry_id, alert_order),
    CONSTRAINT fk_partograph_alert_entry FOREIGN KEY (entry_id) REFERENCES clinical.labor_partograph_entries(id)
);

CREATE TABLE IF NOT EXISTS clinical.delivery_records (
    id                              UUID          NOT NULL DEFAULT gen_random_uuid(),
    episode_id                      UUID          NOT NULL,
    patient_id                      UUID          NOT NULL,
    hospital_id                     UUID          NOT NULL,
    delivered_by_staff_id           UUID,
    documented_by_user_id           UUID,
    birth_date_time                 TIMESTAMP     NOT NULL,
    delivery_mode                   VARCHAR(30)   NOT NULL,
    live_birth                      BOOLEAN       NOT NULL DEFAULT true,
    number_of_infants               INTEGER       NOT NULL DEFAULT 1,
    infant_sex                      VARCHAR(20),
    birth_weight_grams              INTEGER,
    gestational_age_weeks_at_birth  INTEGER,
    apgar_one_minute                INTEGER,
    apgar_five_minute               INTEGER,
    placenta_delivered_at           TIMESTAMP,
    placenta_complete               BOOLEAN,
    uterotonic_given                BOOLEAN,
    estimated_blood_loss_ml         INTEGER,
    perineal_tear                   VARCHAR(20),
    notes                           TEXT,
    created_at                      TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_delivery_records          PRIMARY KEY (id),
    CONSTRAINT uk_delivery_record_episode   UNIQUE (episode_id),
    CONSTRAINT fk_delivery_record_episode   FOREIGN KEY (episode_id)            REFERENCES clinical.labor_episodes(id),
    CONSTRAINT fk_delivery_record_patient   FOREIGN KEY (patient_id)            REFERENCES clinical.patients(id),
    CONSTRAINT fk_delivery_record_hospital  FOREIGN KEY (hospital_id)           REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_delivery_record_staff     FOREIGN KEY (delivered_by_staff_id) REFERENCES hospital.staff(id) ON DELETE NO ACTION,
    CONSTRAINT fk_delivery_record_user      FOREIGN KEY (documented_by_user_id) REFERENCES security.users(id) ON DELETE NO ACTION
);

CREATE INDEX IF NOT EXISTS idx_delivery_record_patient  ON clinical.delivery_records (patient_id);
CREATE INDEX IF NOT EXISTS idx_delivery_record_hospital ON clinical.delivery_records (hospital_id);
CREATE INDEX IF NOT EXISTS idx_delivery_record_birth    ON clinical.delivery_records (birth_date_time);

CREATE TABLE IF NOT EXISTS clinical.delivery_record_alerts (
    delivery_record_id  UUID          NOT NULL,
    alert_order         INTEGER       NOT NULL,
    alert_type          VARCHAR(32)   NOT NULL,
    alert_severity      VARCHAR(16)   NOT NULL,
    alert_code          VARCHAR(64),
    alert_message       TEXT          NOT NULL,
    triggered_by        VARCHAR(120),
    created_at          TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_delivery_record_alerts PRIMARY KEY (delivery_record_id, alert_order),
    CONSTRAINT fk_delivery_alert_record FOREIGN KEY (delivery_record_id) REFERENCES clinical.delivery_records(id)
);

-- Newborn assessment → delivery back-link (audit line 28's blocked FK,
-- now satisfiable). Nullable by design; see entity javadoc.
ALTER TABLE clinical.newborn_assessments
    ADD COLUMN IF NOT EXISTS delivery_record_id UUID NULL;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_newborn_assessment_delivery_record') THEN
        ALTER TABLE clinical.newborn_assessments
            ADD CONSTRAINT fk_newborn_assessment_delivery_record
            FOREIGN KEY (delivery_record_id)
            REFERENCES clinical.delivery_records(id)
            ON DELETE NO ACTION;
    END IF;
END$$;

CREATE INDEX IF NOT EXISTS idx_newborn_assessment_delivery
    ON clinical.newborn_assessments (delivery_record_id);

COMMENT ON COLUMN clinical.newborn_assessments.delivery_record_id IS
    'Delivery event this assessment belongs to (V111). Null for infants born before the L&D module or transferred in.';
