-- =============================================================
-- V116: Close the two open links in the critical-value loop.
--
-- P0 #5 required notify -> READ-BACK -> timer/escalation. What
-- shipped was notify -> one nudge -> silence.
--
-- (1) READ-BACK was never built. The acknowledge endpoint takes no
--     body, so nothing recorded WHAT the clinician was told. The
--     point of a read-back is that the receiver repeats the value
--     and the system checks it — that is what catches a
--     transcription error before someone treats the wrong number.
--     Recording "acknowledged: true" proves a button was clicked
--     and nothing more.
--
-- (2) ESCALATION ESCALATED TO NOBODY. escalateOverdue re-notified
--     the SAME ordering provider who had already ignored the first
--     alert, then stamped critical_escalated_at — which the sweep
--     query uses as an exclusion. So a critical result that nobody
--     ever acknowledges produced exactly two notifications, both to
--     the same person, and then went quiet permanently. For a
--     critical potassium on an off-shift provider that is the whole
--     safety net failing silently.
--
--     critical_escalation_level replaces the one-shot boolean
--     semantics: the sweep re-fires while the result is
--     unacknowledged, widening recipients as the level climbs.
--     critical_escalated_at changes meaning from "escalated (once)"
--     to "last escalated at", which is what the interval is
--     measured from.
--
-- All columns are additive and nullable (or defaulted), so the
-- migration is idempotent and safe to re-run. Existing rows get
-- level 0; any that were already stamped are treated as level 1 so
-- the sweep resumes from where the old one-shot logic stopped
-- rather than re-notifying from scratch.
-- =============================================================

ALTER TABLE lab.lab_results
    ADD COLUMN IF NOT EXISTS critical_escalation_level SMALLINT NOT NULL DEFAULT 0;

-- The value the clinician repeated back, stored verbatim so a
-- mismatch is auditable rather than merely rejected.
ALTER TABLE lab.lab_results
    ADD COLUMN IF NOT EXISTS critical_readback_value VARCHAR(255);

ALTER TABLE lab.lab_results
    ADD COLUMN IF NOT EXISTS critical_readback_at TIMESTAMP WITHOUT TIME ZONE;

ALTER TABLE lab.lab_results
    ADD COLUMN IF NOT EXISTS critical_readback_by_user_id UUID;

ALTER TABLE lab.lab_results
    ADD COLUMN IF NOT EXISTS critical_readback_by_display VARCHAR(255);

-- Rows the old one-shot logic already escalated are level 1, not 0:
-- they have had their single nudge, so the next sweep should widen
-- rather than repeat it.
UPDATE lab.lab_results
   SET critical_escalation_level = 1
 WHERE critical_escalated_at IS NOT NULL
   AND critical_escalation_level = 0;

-- The sweep now selects on (unacknowledged, notified, last-escalated
-- older than the interval), so it reads critical_escalated_at on
-- every pass instead of only checking it for NULL.
CREATE INDEX IF NOT EXISTS idx_lab_results_critical_sweep
    ON lab.lab_results (acknowledged, critical_notified_at, critical_escalated_at);
