-- V222 (QA D605): an URGENT obstetric or neonatal alert (partograph, delivery,
-- newborn assessment, postpartum observation) reached only the clinician who
-- typed it. Each one now has an escalation row: the hospital's on-call team
-- (or its doctors and midwives) is notified and gets an in-basket item to
-- acknowledge, and a sweep re-notifies a wider circle until someone does or
-- the round cap is reached.
-- No patient name or other identifier is stored here: the message is rebuilt
-- from the patient record when a reminder is sent. Plain statements, no DO block.

CREATE TABLE IF NOT EXISTS clinical.obstetric_alert_escalations (
    id                    UUID PRIMARY KEY,
    hospital_id           UUID NOT NULL,
    patient_id            UUID NOT NULL,
    source_type           VARCHAR(32) NOT NULL,
    source_id             UUID NOT NULL,
    alert_type            VARCHAR(64),
    type_label            VARCHAR(255),
    alert_message         TEXT,
    documented_by_user_id UUID,
    escalation_level      SMALLINT NOT NULL DEFAULT 0,
    last_escalation_at    TIMESTAMP WITHOUT TIME ZONE,
    acknowledged_at       TIMESTAMP WITHOUT TIME ZONE,
    acknowledged_by       UUID,
    created_at            TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT chk_oae_source_type CHECK (source_type IN
        ('PARTOGRAPH_ENTRY', 'DELIVERY', 'NEWBORN_ASSESSMENT', 'POSTPARTUM_OBSERVATION'))
);

CREATE INDEX IF NOT EXISTS idx_oae_open
    ON clinical.obstetric_alert_escalations (acknowledged_at, escalation_level, last_escalation_at);
CREATE INDEX IF NOT EXISTS idx_oae_hospital ON clinical.obstetric_alert_escalations (hospital_id);
