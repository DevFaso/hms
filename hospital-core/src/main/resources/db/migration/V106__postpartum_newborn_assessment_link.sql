-- =================================================================
-- V106 — Cross-service link: PostpartumCarePlan → NewbornAssessment.
-- Roadmap row 41 follow-on item (1) "cross-service workflow integration".
--
-- The OB/GYN + pediatrics scope-audit doc
-- (docs/runbooks/obgyn-pediatrics-finish-scope-audit.md) names three
-- cross-service FKs as missing:
--   - Pregnancy.outcome enum   — deferred (no Pregnancy entity yet;
--     would need its own foundation pass — currently only
--     MaternalHistory exists with text fields).
--   - NewbornAssessment.parentPregnancyId FK — same blocker.
--   - PostpartumCarePlan.newbornAssessmentId FK — both entities
--     exist today; this migration ships that link.
--
-- The link is nullable: a postpartum care plan can predate the
-- newborn assessment (the assessment is documented after delivery),
-- and not every postpartum plan tracks a newborn (e.g. fetal loss,
-- adoption). When the link is populated, the downstream
-- PostpartumCare→ImmunizationService auto-enqueue (named follow-on
-- item) reads the newborn_assessment_id to know which infant's
-- immunizations to schedule.
--
-- The FK is application-side soft (NO ACTION on delete) so deleting
-- a NewbornAssessment does NOT cascade-delete the postpartum plan;
-- the operator must explicitly null the link first. Same defensive
-- shape as the other cross-service FKs in the clinical schema.
--
-- Forward-only; no automated rollback declared. Strictly additive
-- (ADD COLUMN IF NOT EXISTS + DO-block FK guard).
-- =================================================================

ALTER TABLE clinical.postpartum_care_plans
    ADD COLUMN IF NOT EXISTS newborn_assessment_id UUID NULL;

-- FK guard via DO block — clean idempotency on re-runs (the
-- PostgreSQL FK creation has no IF NOT EXISTS form on its own).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_postpartum_plan_newborn_assessment'
    ) THEN
        ALTER TABLE clinical.postpartum_care_plans
            ADD CONSTRAINT fk_postpartum_plan_newborn_assessment
            FOREIGN KEY (newborn_assessment_id)
            REFERENCES clinical.newborn_assessments(id)
            ON DELETE NO ACTION;
    END IF;
END$$;

CREATE INDEX IF NOT EXISTS idx_postpartum_plan_newborn_assessment
    ON clinical.postpartum_care_plans (newborn_assessment_id);

COMMENT ON COLUMN clinical.postpartum_care_plans.newborn_assessment_id IS
    'Row 41 follow-on. Nullable FK to clinical.newborn_assessments — link from the maternal postpartum plan to the infant''s newborn assessment. Drives the PostpartumCare→ImmunizationService auto-enqueue path (named row-41 follow-on). Nullable for fetal loss / adoption / plans-recorded-before-assessment cases.';
