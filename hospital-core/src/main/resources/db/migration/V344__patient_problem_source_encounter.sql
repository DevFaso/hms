-- V344: QA D2730 (r65, High) - the visit a problem was recorded in.
--
-- A problem carried its own sensitivity tag only, and nothing wrote it: a
-- doctor who added « B20 Maladie due au VIH » to the problem list during an
-- HIV-tagged visit produced an untagged row, which a caregiver with family
-- access then read under « Pathologies actives ».
--
-- The problem now names the visit it was recorded in, so its category follows
-- that visit's (its tag, else its department's default) when the row has no
-- tag of its own. NULL, the default, keeps today's behaviour: every existing
-- row stays as it was (no backfill - which visit an old problem came from is
-- not recorded anywhere, and guessing it would be fabricating a classification).
-- Additive and idempotent: IF NOT EXISTS, no DO block.

ALTER TABLE clinical.patient_problems
    ADD COLUMN IF NOT EXISTS source_encounter_id UUID;

ALTER TABLE clinical.patient_problems
    DROP CONSTRAINT IF EXISTS fk_problem_source_encounter;

ALTER TABLE clinical.patient_problems
    ADD CONSTRAINT fk_problem_source_encounter
        FOREIGN KEY (source_encounter_id) REFERENCES clinical.encounters(id);

CREATE INDEX IF NOT EXISTS idx_problem_source_encounter
    ON clinical.patient_problems (source_encounter_id)
    WHERE source_encounter_id IS NOT NULL;
