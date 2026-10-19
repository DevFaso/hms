-- V193: clinical.encounters - link an inpatient encounter to its admission
-- (QA run 2026-10-18 round 3, defects D134/D135/D136). An admission now opens
-- its own INPATIENT encounter, so notes, the discharge summary and the
-- after-visit summary have a stay to hang on, and a discharge closes that
-- encounter only, never the patient's unrelated outpatient visit.

ALTER TABLE clinical.encounters ADD COLUMN IF NOT EXISTS admission_id UUID;

CREATE INDEX IF NOT EXISTS idx_encounter_admission ON clinical.encounters (admission_id);
