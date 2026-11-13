-- V292 (QA D1762): the labour transfer and arrival urgent notice (#1134)
-- files its escalation row with source_type 'LABOR_TRANSFER', which the V222
-- CHECK did not allow: every transfer and arrival failed the insert, so the
-- first CRITICAL notice went out but nothing was ever re-notified.
-- Widen the CHECK to every source ObstetricAlertEscalationService can write
-- (ObstetricAlertEscalationSourceTypeGuardTest keeps the two in step).
-- Idempotent: drop if it exists, then re-add. Plain statements, no DO block.

ALTER TABLE clinical.obstetric_alert_escalations
    DROP CONSTRAINT IF EXISTS chk_oae_source_type;

ALTER TABLE clinical.obstetric_alert_escalations
    ADD CONSTRAINT chk_oae_source_type CHECK (source_type IN
        ('PARTOGRAPH_ENTRY', 'DELIVERY', 'NEWBORN_ASSESSMENT', 'POSTPARTUM_OBSERVATION', 'LABOR_TRANSFER'));
