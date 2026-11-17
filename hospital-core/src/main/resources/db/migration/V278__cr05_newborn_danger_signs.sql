-- V278: CR-05. Structured danger signs on the newborn assessment: present, absent or not assessed.
-- Part of the provisional WHO postnatal pathway (not clinically approved, awaiting the named approver),
-- and not all of it: early jaundice is not recorded here.
--
-- An existing row reads NOT_ASSESSED, which is what it holds: nobody recorded these signs on it, and that
-- never means they were absent. No existing value is rewritten. Idempotent by rule.

ALTER TABLE clinical.newborn_assessments
    ADD COLUMN IF NOT EXISTS poor_feeding VARCHAR(16) NOT NULL DEFAULT 'NOT_ASSESSED'
        CONSTRAINT chk_newborn_poor_feeding CHECK (poor_feeding IN ('PRESENT', 'ABSENT', 'NOT_ASSESSED')),
    ADD COLUMN IF NOT EXISTS convulsions VARCHAR(16) NOT NULL DEFAULT 'NOT_ASSESSED'
        CONSTRAINT chk_newborn_convulsions CHECK (convulsions IN ('PRESENT', 'ABSENT', 'NOT_ASSESSED')),
    ADD COLUMN IF NOT EXISTS severe_chest_indrawing VARCHAR(16) NOT NULL DEFAULT 'NOT_ASSESSED'
        CONSTRAINT chk_newborn_severe_chest_indrawing CHECK (severe_chest_indrawing IN ('PRESENT', 'ABSENT', 'NOT_ASSESSED')),
    ADD COLUMN IF NOT EXISTS absent_spontaneous_movement VARCHAR(16) NOT NULL DEFAULT 'NOT_ASSESSED'
        CONSTRAINT chk_newborn_absent_spontaneous_movement CHECK (absent_spontaneous_movement IN ('PRESENT', 'ABSENT', 'NOT_ASSESSED'));
