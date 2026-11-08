-- V259 (QA round 25: D1240, D1241, D1242, D1247).
-- A record withdrawn from a patient's chart keeps why, when and by whom:
--   * an allergy deactivation (D1242: the reason was only logged, so a
--     second deactivation could "replace" it; a second one is now refused),
--   * a guarantor deactivation (D1247: one click, no reason),
--   * a birth plan withdrawn after a provider reviewed it (D1241: it was
--     hard-deleted; a reviewed plan is now kept and marked withdrawn), with
--     the plan's author so an unreviewed draft is deleted only by its author,
--   * a diagnosis removed as entered in error (D1240: the patient's medical
--     history still listed it). The flag is backfilled from the problem
--     history: a problem whose latest status change is the removal.
-- Existing rows keep null reasons: nothing was recorded for them.
-- Plain statements, no DO block. Idempotent.

ALTER TABLE clinical.patient_allergies
    ADD COLUMN IF NOT EXISTS deactivation_reason VARCHAR(500);
ALTER TABLE clinical.patient_allergies
    ADD COLUMN IF NOT EXISTS deactivated_at TIMESTAMP;
ALTER TABLE clinical.patient_allergies
    ADD COLUMN IF NOT EXISTS deactivated_by_user_id UUID;

ALTER TABLE clinical.patient_guarantors
    ADD COLUMN IF NOT EXISTS deactivation_reason VARCHAR(500);
ALTER TABLE clinical.patient_guarantors
    ADD COLUMN IF NOT EXISTS deactivated_at TIMESTAMP;
ALTER TABLE clinical.patient_guarantors
    ADD COLUMN IF NOT EXISTS deactivated_by_user_id UUID;

ALTER TABLE clinical.birth_plans
    ADD COLUMN IF NOT EXISTS created_by_user_id UUID;
ALTER TABLE clinical.birth_plans
    ADD COLUMN IF NOT EXISTS withdrawn_at TIMESTAMP;
ALTER TABLE clinical.birth_plans
    ADD COLUMN IF NOT EXISTS withdrawn_by_user_id UUID;
ALTER TABLE clinical.birth_plans
    ADD COLUMN IF NOT EXISTS withdrawal_reason VARCHAR(500);

-- A plan a provider signed before this release counts as reviewed.
UPDATE clinical.birth_plans
SET review_timestamp = provider_signature_date
WHERE review_timestamp IS NULL
  AND provider_signature_date IS NOT NULL;

ALTER TABLE clinical.patient_problems
    ADD COLUMN IF NOT EXISTS entered_in_error BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE clinical.patient_problems p
SET entered_in_error = TRUE
WHERE p.entered_in_error = FALSE
  AND p.status = 'INACTIVE'
  AND (
      SELECT h.change_type
      FROM clinical.patient_problem_history h
      WHERE h.problem_id = p.id
        AND h.change_type IN ('CREATED', 'STATUS_CHANGED', 'DELETED')
      ORDER BY h.changed_at DESC
      LIMIT 1
  ) = 'DELETED';
