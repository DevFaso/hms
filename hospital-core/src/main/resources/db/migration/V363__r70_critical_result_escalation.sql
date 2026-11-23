-- V363 (QA r70 scorecard C5), provisional rule CRITICAL_RESULT_ESCALATION (research report « Transfusion and
-- critical result cover », section 2). Additive, IF NOT EXISTS, plain statements, no DO block.

-- 1. The steps of the escalation ladder, per hospital, in minutes after the first notice. NULL = the default
--    (15, 30, 60). The médecin-chef the +30 minute step tells; NULL = the heads of department, else every doctor.
ALTER TABLE hospital.hospitals ADD COLUMN IF NOT EXISTS critical_cover_after_minutes INTEGER;
ALTER TABLE hospital.hospitals ADD COLUMN IF NOT EXISTS critical_lead_after_minutes INTEGER;
ALTER TABLE hospital.hospitals ADD COLUMN IF NOT EXISTS critical_unresolved_after_minutes INTEGER;
ALTER TABLE hospital.hospitals ADD COLUMN IF NOT EXISTS critical_medical_lead_user_id UUID;

-- 2. Who acknowledged, as what and how: the name and the time are already on the row (V1, V116); the role the
--    clinician acted under and the channel the result reached them by were not.
ALTER TABLE lab.lab_results ADD COLUMN IF NOT EXISTS critical_ack_role VARCHAR(40);
ALTER TABLE lab.lab_results ADD COLUMN IF NOT EXISTS critical_ack_channel VARCHAR(20);

-- 3. One row per critical result nobody acknowledged within the last step: the incident. It carries no value and
--    no test name (a hospital administrator may read it); the result itself is read by clinicians only.
CREATE TABLE IF NOT EXISTS lab.critical_result_incidents (
    id                     UUID         NOT NULL DEFAULT gen_random_uuid(),
    lab_result_id          UUID         NOT NULL,
    hospital_id            UUID         NOT NULL,
    opened_at              TIMESTAMP    NOT NULL,
    minutes_unacknowledged INTEGER      NOT NULL,
    care_setting           VARCHAR(20)  NOT NULL,
    recipients_told        INTEGER      NOT NULL DEFAULT 0,
    resolved_at            TIMESTAMP,
    resolved_by_user_id    UUID,
    resolved_by_display    VARCHAR(255),
    created_at             TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at             TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_critical_result_incidents      PRIMARY KEY (id),
    CONSTRAINT uq_critical_result_incident       UNIQUE (lab_result_id),
    CONSTRAINT fk_critical_incident_result       FOREIGN KEY (lab_result_id) REFERENCES lab.lab_results(id),
    CONSTRAINT fk_critical_incident_hospital     FOREIGN KEY (hospital_id)   REFERENCES hospital.hospitals(id),
    CONSTRAINT ck_critical_incident_setting      CHECK (care_setting IN ('INPATIENT', 'EMERGENCY', 'OUTPATIENT'))
);

CREATE INDEX IF NOT EXISTS idx_critical_incident_hospital
    ON lab.critical_result_incidents (hospital_id, opened_at);
