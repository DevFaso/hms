-- r53 (user decision 2026-11-14, PROVISIONAL, pending the obstetric lead's and
-- the child-protection lead's approval): the labour admission of a minor under
-- the configured age (provisional default: under 13 at admission) opens a
-- safeguarding review. Admission and care are never blocked or delayed by it.
--
-- One row per review. Ids only, no names: the reviewers' copy is rebuilt from
-- the records. The review note is a clinical narrative, encrypted by the
-- application (EncryptedStringConverter). Readable only through
-- SafeguardingReviewController, by the treating team and the hospital's
-- administrators, at the review's own hospital.

CREATE TABLE IF NOT EXISTS clinical.safeguarding_reviews (
    id                    UUID         NOT NULL DEFAULT gen_random_uuid(),
    hospital_id           UUID         NOT NULL,
    patient_id            UUID         NOT NULL,
    trigger_code          VARCHAR(40)  NOT NULL,
    source_type           VARCHAR(40)  NOT NULL,
    source_id             UUID         NOT NULL,
    patient_age_years     INTEGER      NOT NULL,
    age_threshold_years   INTEGER      NOT NULL,
    raised_at             TIMESTAMP    NOT NULL,
    raised_by_user_id     UUID,
    status                VARCHAR(20)  NOT NULL,
    reviewed_at           TIMESTAMP,
    reviewed_by_user_id   UUID,
    review_note           TEXT,
    created_at            TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_safeguarding_reviews          PRIMARY KEY (id),
    CONSTRAINT ck_safeguarding_review_status    CHECK (status IN ('OPEN', 'REVIEWED')),
    CONSTRAINT ck_safeguarding_review_trigger   CHECK (trigger_code IN ('MINOR_LABOUR')),
    CONSTRAINT ck_safeguarding_review_source    CHECK (source_type IN ('ADMISSION', 'LABOR_EPISODE')),
    CONSTRAINT ck_safeguarding_review_done      CHECK (status = 'OPEN'
        OR (reviewed_at IS NOT NULL AND reviewed_by_user_id IS NOT NULL AND review_note IS NOT NULL)),
    CONSTRAINT fk_safeguarding_review_hospital  FOREIGN KEY (hospital_id)         REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_safeguarding_review_patient   FOREIGN KEY (patient_id)          REFERENCES clinical.patients(id),
    CONSTRAINT fk_safeguarding_review_raised_by FOREIGN KEY (raised_by_user_id)   REFERENCES security.users(id),
    CONSTRAINT fk_safeguarding_review_reviewer  FOREIGN KEY (reviewed_by_user_id) REFERENCES security.users(id)
);

CREATE INDEX IF NOT EXISTS idx_safeguarding_review_hospital_status
    ON clinical.safeguarding_reviews (hospital_id, status);
CREATE INDEX IF NOT EXISTS idx_safeguarding_review_patient
    ON clinical.safeguarding_reviews (patient_id, hospital_id);

-- One open review per patient, hospital and trigger: the admission and the
-- labour episode of the same stay raise one review, whatever the order or race.
CREATE UNIQUE INDEX IF NOT EXISTS uq_safeguarding_review_open
    ON clinical.safeguarding_reviews (patient_id, hospital_id, trigger_code)
    WHERE status = 'OPEN';
