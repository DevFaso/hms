-- V364 (QA r70 scorecard C5, review of #1291), provisional rule CRITICAL_RESULT_ESCALATION. Additive, plain
-- statements, no DO block, idempotent.

-- 1. The results already waiting when the ladder is switched on. Before this rule the sweep stopped after twelve
--    rounds, so a database may hold critical results nobody acknowledged, some for weeks. They stay on the ladder
--    (an unacknowledged critical result is never dropped: the in-application notices and the incident still
--    come), but the ladder never sends an SMS for them: the first sweeps after a deploy must not text whole
--    chains of clinicians about old results. Marked here, once. A result paged after this migration is not.
ALTER TABLE lab.lab_results ADD COLUMN IF NOT EXISTS critical_backlog BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE lab.lab_results
   SET critical_backlog = TRUE
 WHERE critical_notified_at IS NOT NULL
   AND acknowledged = FALSE
   AND critical_readback_at IS NULL
   AND critical_backlog = FALSE;

-- 2. How an incident ended. V363 knew one ending: the read-back of a clinician. An incident also ends when the result
--    is amended (the unresolved episode was about the old value) or no longer grades as critical. Nobody
--    acknowledged then, so the row says why it closed, with no name on it.
--    (A CHECK passes on NULL: the reason of a closed row is required in so many words, IS NOT NULL.)
ALTER TABLE lab.critical_result_incidents ADD COLUMN IF NOT EXISTS resolution VARCHAR(30);

UPDATE lab.critical_result_incidents
   SET resolution = 'READ_BACK'
 WHERE resolved_at IS NOT NULL
   AND resolution IS NULL;

ALTER TABLE lab.critical_result_incidents
    DROP CONSTRAINT IF EXISTS ck_critical_incident_resolution;

ALTER TABLE lab.critical_result_incidents
    ADD CONSTRAINT ck_critical_incident_resolution CHECK (
        (resolved_at IS NULL AND resolution IS NULL)
        OR (resolved_at IS NOT NULL AND resolution IS NOT NULL
            AND resolution IN ('READ_BACK', 'AMENDED', 'NO_LONGER_CRITICAL')));

-- 3. A result amended to another critical value starts a new chain, and may go unanswered again: a second
--    incident for the same result. One OPEN incident per result, any number of closed ones.
ALTER TABLE lab.critical_result_incidents
    DROP CONSTRAINT IF EXISTS uq_critical_result_incident;

CREATE UNIQUE INDEX IF NOT EXISTS uq_critical_result_incident_open
    ON lab.critical_result_incidents (lab_result_id)
    WHERE resolved_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_critical_incident_result
    ON lab.critical_result_incidents (lab_result_id);
