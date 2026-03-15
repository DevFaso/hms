-- V13: Add abnormal_flag to lab results, code_status to patients, urgency to encounters
-- These columns support real clinical-triage data in the Physician Cockpit.

-- 1. Abnormal flag on lab results (AbnormalFlag enum: NORMAL, ABNORMAL, CRITICAL)
ALTER TABLE lab.lab_results
    ADD COLUMN IF NOT EXISTS abnormal_flag VARCHAR(20) NULL;

-- 2. Code status on patients (e.g. FULL_CODE, DNR, DNI, COMFORT_ONLY)
ALTER TABLE clinical.patients
    ADD COLUMN IF NOT EXISTS code_status VARCHAR(30) NULL;

-- 3. Clinical triage urgency on encounters (EncounterUrgency enum: EMERGENT, URGENT, ROUTINE, LOW)
ALTER TABLE clinical.encounters
    ADD COLUMN IF NOT EXISTS urgency VARCHAR(20) NULL;
