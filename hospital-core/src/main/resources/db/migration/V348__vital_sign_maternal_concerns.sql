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
