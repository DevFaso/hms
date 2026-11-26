-- V386 (scorecard K4): one escalation ladder for every urgent clinical alert. Plain statements, no DO block, idempotent.
--
-- 1. The first notice of an urgent alert is owed until it is delivered. The row is written with the clinical
--    record; first_notice_filed_at stays NULL until the circle has its notices, and the sweep delivers a row
--    left owed. named_recipients and already_rang hold the usernames the raising module named or already rang,
--    so a later delivery reaches the same circle. Rows that exist today were delivered when they were raised.
ALTER TABLE clinical.obstetric_alert_escalations ADD COLUMN IF NOT EXISTS first_notice_filed_at TIMESTAMP;
ALTER TABLE clinical.obstetric_alert_escalations ADD COLUMN IF NOT EXISTS named_recipients JSONB;
ALTER TABLE clinical.obstetric_alert_escalations ADD COLUMN IF NOT EXISTS already_rang JSONB;

UPDATE clinical.obstetric_alert_escalations
   SET first_notice_filed_at = COALESCE(created_at, now())
 WHERE first_notice_filed_at IS NULL;

-- 2. The ladder no longer stops after a number of reminders. An alert that had already used its rounds under the
--    old rule, and was left unacknowledged, is marked once: the first sweeps after a deploy must not open an
--    incident and page a whole chain about old alerts. An alert raised after this migration is never marked.
ALTER TABLE clinical.obstetric_alert_escalations ADD COLUMN IF NOT EXISTS ladder_backlog BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE clinical.obstetric_alert_escalations
   SET ladder_backlog = TRUE
 WHERE acknowledged_at IS NULL
   AND escalation_level >= 3
   AND ladder_backlog = FALSE;

-- 3. The sources the ladder now carries: a severe transfusion reaction, a positive culture, an adult's urgent
--    hypertension, and a countersignature past its deadline (emergency O positive, incompatible crossmatch).
--    ObstetricAlertEscalationSourceTypeGuardTest keeps this list in step with the service.
ALTER TABLE clinical.obstetric_alert_escalations
    DROP CONSTRAINT IF EXISTS chk_oae_source_type;

ALTER TABLE clinical.obstetric_alert_escalations
    ADD CONSTRAINT chk_oae_source_type CHECK (source_type IN
        ('PARTOGRAPH_ENTRY', 'DELIVERY', 'NEWBORN_ASSESSMENT', 'POSTPARTUM_OBSERVATION', 'LABOR_TRANSFER',
         'VITAL_SIGN', 'NEWS2_VITAL_SIGN', 'NEWBORN_VITAL_SIGN',
         'TRANSFUSION_REACTION', 'POSITIVE_CULTURE', 'URGENT_HYPERTENSION',
         'O_POSITIVE_COUNTERSIGN', 'INCOMPATIBLE_COUNTERSIGN'));

CREATE INDEX IF NOT EXISTS idx_oae_open_source
    ON clinical.obstetric_alert_escalations (source_type, source_id)
    WHERE acknowledged_at IS NULL;

-- 4. The register of urgent alerts nobody acknowledged by the last step of the ladder. It names its alert by id
--    only (no patient, no value, no text), so an administrator may read the register without reading a chart.
--    One OPEN row per alert, any number of closed ones.
CREATE TABLE IF NOT EXISTS clinical.urgent_alert_incidents (
    id                     UUID         NOT NULL DEFAULT gen_random_uuid(),
    source_type            VARCHAR(32)  NOT NULL,
    source_id              UUID         NOT NULL,
    hospital_id            UUID         NOT NULL,
    opened_at              TIMESTAMP    NOT NULL,
    minutes_unacknowledged INTEGER      NOT NULL,
    recipients_told        INTEGER      NOT NULL DEFAULT 0,
    resolved_at            TIMESTAMP,
    resolved_by_user_id    UUID,
    resolution             VARCHAR(30),
    created_at             TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at             TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_urgent_alert_incidents     PRIMARY KEY (id),
    CONSTRAINT fk_urgent_incident_hospital   FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals(id),
    CONSTRAINT ck_urgent_incident_source     CHECK (source_type IN ('URGENT_ALERT', 'IMAGING_REPORT', 'PRO_RESPONSE')),
    CONSTRAINT ck_urgent_incident_resolution CHECK (
        (resolved_at IS NULL AND resolution IS NULL)
        OR (resolved_at IS NOT NULL AND resolution IS NOT NULL
            AND resolution IN ('ACKNOWLEDGED', 'NO_LONGER_URGENT')))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_urgent_alert_incident_open
    ON clinical.urgent_alert_incidents (source_type, source_id)
    WHERE resolved_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_urgent_incident_hospital
    ON clinical.urgent_alert_incidents (hospital_id, opened_at);

CREATE INDEX IF NOT EXISTS idx_urgent_incident_source
    ON clinical.urgent_alert_incidents (source_type, source_id);
