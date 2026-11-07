-- V277: orders existing when a recorded death is disputed need a prescriber's reconfirmation before use (user
-- ruling, round 36). A row per order, snapshotted in the dispute's own transaction, and never by the death date,
-- which may itself be wrong. The row outlives the dispute: neither outcome of a dispute clears it.
--
-- RUNNING_TRANSFUSION rows are not holds: treatment already running goes on, flagged until a clinician records an
-- assessment. Idempotent by rule.

ALTER TABLE clinical.transfusion_requests
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS clinical.order_review_holds (
    id                      UUID          NOT NULL DEFAULT gen_random_uuid(),
    dispute_id              UUID          NOT NULL,
    hospital_id             UUID          NOT NULL,
    patient_id              UUID          NOT NULL,
    order_type              VARCHAR(30)   NOT NULL,
    order_id                UUID          NOT NULL,
    order_version           BIGINT        NOT NULL,
    status                  VARCHAR(20)   NOT NULL,
    reviewed_order_version  BIGINT,
    decided_by_id           UUID,
    decided_by_name         VARCHAR(255),
    decided_at              TIMESTAMP,
    reason                  VARCHAR(1000),
    replacement_order_id    UUID,
    created_at              TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_order_review_holds       PRIMARY KEY (id),
    CONSTRAINT fk_order_review_dispute     FOREIGN KEY (dispute_id)  REFERENCES clinical.death_disputes(id),
    CONSTRAINT fk_order_review_hospital    FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_order_review_patient     FOREIGN KEY (patient_id)  REFERENCES clinical.patients(id),
    CONSTRAINT ck_order_review_type        CHECK (order_type IN ('PRESCRIPTION', 'TRANSFUSION_REQUEST', 'RUNNING_TRANSFUSION')),
    CONSTRAINT ck_order_review_status      CHECK (status IN ('HELD', 'CONTINUED', 'REPLACED', 'STOPPED', 'ASSESSMENT_DUE', 'ASSESSED'))
);

-- One standing hold per order, however many disputes pass over it.
CREATE UNIQUE INDEX IF NOT EXISTS uq_order_review_standing
    ON clinical.order_review_holds (order_type, order_id)
    WHERE status IN ('HELD', 'ASSESSMENT_DUE');

CREATE INDEX IF NOT EXISTS idx_order_review_patient
    ON clinical.order_review_holds (patient_id, hospital_id);

CREATE INDEX IF NOT EXISTS idx_order_review_dispute
    ON clinical.order_review_holds (dispute_id);
