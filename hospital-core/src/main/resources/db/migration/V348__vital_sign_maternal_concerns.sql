-- V348 (policy 2026-11-20, provisional maternal early warning score): the
-- "additional concerns" of the NHS England national MEWS chart a recorder may
-- tick with a vital set (staff or family concern, added therapy such as
-- oxygen, more pain, significant vaginal bleeding, reduced urine output,
-- decreased consciousness), stored as MaternalConcern names, comma-separated.
-- They raise the level of concern of a pregnant or postpartum woman; they are
-- never points. Optional: NULL means none was ticked, and no existing row is
-- touched. Idempotent.

ALTER TABLE clinical.patient_vital_signs
    ADD COLUMN IF NOT EXISTS maternal_concerns VARCHAR(200);

-- The page cool-down of the same policy (decision 2026-11-20: one page per woman
-- and per level in 30 minutes): the level a reading paged at (LOW_MEDIUM,
-- MEDIUM, HIGH) and when. NULL means the reading paged nobody. Never a score:
-- the score is computed on read. Idempotent.

ALTER TABLE clinical.patient_vital_signs
    ADD COLUMN IF NOT EXISTS mews_paged_tier VARCHAR(20);

ALTER TABLE clinical.patient_vital_signs
    ADD COLUMN IF NOT EXISTS mews_paged_at TIMESTAMP;
