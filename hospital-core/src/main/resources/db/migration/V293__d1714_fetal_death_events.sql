-- QA D1714 (round 46), user decision 2026-11-13: a recorded stillbirth creates
-- a linked fetal-death event that needs no patient account.
--
-- 1. One outcome per baby (LIVE or STILLBORN), so mixed twins can be recorded.
--    A delivery written before this has no rows here: its single live_birth
--    flag still answers for it.
-- 2. One fetal-death event per stillborn baby, linked to the delivery, the
--    mother and the baby's order. A correction never deletes one: it is
--    superseded, with who, when and why. At most one live (not superseded)
--    event per baby.
-- 3. A death record that references the same fetal death is linked, so the
--    register counts it once.
--
-- The events of deliveries recorded before this (live_birth = false) are
-- created by FetalDeathEventBackfill at startup, from the recorded facts only.

CREATE TABLE IF NOT EXISTS clinical.delivery_record_baby_outcomes (
    delivery_record_id UUID        NOT NULL,
    birth_order        INTEGER     NOT NULL,
    outcome            VARCHAR(20) NOT NULL,

    CONSTRAINT pk_delivery_baby_outcomes      PRIMARY KEY (delivery_record_id, birth_order),
    CONSTRAINT ck_delivery_baby_outcome_order CHECK (birth_order >= 1),
    CONSTRAINT ck_delivery_baby_outcome       CHECK (outcome IN ('LIVE', 'STILLBORN')),
    CONSTRAINT fk_delivery_baby_outcome_record FOREIGN KEY (delivery_record_id) REFERENCES clinical.delivery_records(id)
);

CREATE TABLE IF NOT EXISTS clinical.fetal_death_events (
    id                       UUID         NOT NULL DEFAULT gen_random_uuid(),
    delivery_record_id       UUID         NOT NULL,
    mother_patient_id        UUID         NOT NULL,
    hospital_id              UUID         NOT NULL,
    birth_order              INTEGER      NOT NULL,
    occurred_at              TIMESTAMP    NOT NULL,
    gestational_age_weeks    INTEGER,
    no_signs_of_life         BOOLEAN      NOT NULL,
    source                   VARCHAR(30)  NOT NULL,
    recorded_by_user_id      UUID,
    creation_reason          VARCHAR(500),
    superseded_at            TIMESTAMP,
    superseded_by_user_id    UUID,
    supersede_reason         VARCHAR(500),
    created_at               TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at               TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_fetal_death_events          PRIMARY KEY (id),
    CONSTRAINT ck_fetal_death_order           CHECK (birth_order >= 1),
    CONSTRAINT ck_fetal_death_source          CHECK (source IN ('DELIVERY', 'DELIVERY_HISTORICAL')),
    CONSTRAINT ck_fetal_death_superseded      CHECK (superseded_at IS NULL OR supersede_reason IS NOT NULL),
    CONSTRAINT fk_fetal_death_delivery        FOREIGN KEY (delivery_record_id)    REFERENCES clinical.delivery_records(id),
    CONSTRAINT fk_fetal_death_mother          FOREIGN KEY (mother_patient_id)     REFERENCES clinical.patients(id),
    CONSTRAINT fk_fetal_death_hospital        FOREIGN KEY (hospital_id)           REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_fetal_death_recorded_by     FOREIGN KEY (recorded_by_user_id)   REFERENCES security.users(id),
    CONSTRAINT fk_fetal_death_superseded_by   FOREIGN KEY (superseded_by_user_id) REFERENCES security.users(id)
);

CREATE INDEX IF NOT EXISTS idx_fetal_death_delivery ON clinical.fetal_death_events (delivery_record_id);
CREATE INDEX IF NOT EXISTS idx_fetal_death_hospital_time ON clinical.fetal_death_events (hospital_id, occurred_at);

-- Idempotent: one live event per baby, whatever retries or instances race.
CREATE UNIQUE INDEX IF NOT EXISTS uq_fetal_death_live_per_baby
    ON clinical.fetal_death_events (delivery_record_id, birth_order)
    WHERE superseded_at IS NULL;

ALTER TABLE clinical.death_records
    ADD COLUMN IF NOT EXISTS fetal_death_event_id UUID;

ALTER TABLE clinical.death_records
    DROP CONSTRAINT IF EXISTS fk_death_record_fetal_death_event;

ALTER TABLE clinical.death_records
    ADD CONSTRAINT fk_death_record_fetal_death_event
        FOREIGN KEY (fetal_death_event_id) REFERENCES clinical.fetal_death_events(id);

CREATE INDEX IF NOT EXISTS idx_death_record_fetal_death_event ON clinical.death_records (fetal_death_event_id);
