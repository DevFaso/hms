-- V375 (QA r77 D3862): the one escalation path of the early-warning scores. Plain statements, no DO block, idempotent.
--
-- An adult NEWS2 in the HIGH band ('NEWS2_VITAL_SIGN') and an URGENT newborn finding on a vital set
-- ('NEWBORN_VITAL_SIGN') are filed as urgent alerts to acknowledge, like the maternal MEWS ('VITAL_SIGN', V362).
-- The V362 CHECK did not allow them. Widen it to every source ObstetricAlertEscalationService can write
-- (ObstetricAlertEscalationSourceTypeGuardTest keeps the two in step). Drop if it exists, then re-add.

ALTER TABLE clinical.obstetric_alert_escalations
    DROP CONSTRAINT IF EXISTS chk_oae_source_type;

ALTER TABLE clinical.obstetric_alert_escalations
    ADD CONSTRAINT chk_oae_source_type CHECK (source_type IN
        ('PARTOGRAPH_ENTRY', 'DELIVERY', 'NEWBORN_ASSESSMENT', 'POSTPARTUM_OBSERVATION', 'LABOR_TRANSFER',
         'VITAL_SIGN', 'NEWS2_VITAL_SIGN', 'NEWBORN_VITAL_SIGN'));

-- The repeat check of a vital set's early warning reads the open alerts of one patient.
CREATE INDEX IF NOT EXISTS idx_oae_open_patient_source
    ON clinical.obstetric_alert_escalations (hospital_id, patient_id, source_type)
    WHERE acknowledged_at IS NULL;
