-- =============================================================
-- V133: critical-imaging-finding notification ledger (Tier 2 item 27).
--
-- WHY
--   V132 gave the radiologist a way to raise a critical finding
--   (critical_result_flagged_at) and item 26 gave a clinician a way to
--   acknowledge one. Nothing in between told anybody there was
--   something to acknowledge. Labs have had the full loop since
--   V109/V116 — notify the ordering provider, escalate on a timer,
--   widen the audience each round — and imaging had the flag, the
--   button, and no chain.
--
--   The escalation ledger could not simply be borrowed: those stamps
--   and rounds live on lab.lab_results, which is exactly the reason
--   the NEWS2 work (V130) deferred tiering vitals through the same
--   service. An imaging report needs its own three columns.
--
-- NO READ-BACK COLUMN, DELIBERATELY
--   The lab loop requires a read-back because a critical lab value is
--   a NUMBER relayed by phone, and repeating it back is what catches a
--   transcription error before somebody treats the wrong figure. A
--   critical imaging finding is prose the clinician reads in the chart;
--   there is no number to mis-transcribe, so a read-back field would be
--   ceremony without a safety property. The radiology analogue —
--   "communicated to Dr X at HH:MM" — is already covered by
--   critical_result_acknowledged_by/at from item 26.
--
-- DEFAULT 0 ON THE LEVEL
--   NOT NULL with a default so the sweep's arithmetic never meets a
--   null, and so rows written between V132 and V133 need no backfill.
-- =============================================================

ALTER TABLE clinical.imaging_reports
    ADD COLUMN IF NOT EXISTS critical_notified_at TIMESTAMP WITHOUT TIME ZONE;

ALTER TABLE clinical.imaging_reports
    ADD COLUMN IF NOT EXISTS critical_escalated_at TIMESTAMP WITHOUT TIME ZONE;

ALTER TABLE clinical.imaging_reports
    ADD COLUMN IF NOT EXISTS critical_escalation_level SMALLINT NOT NULL DEFAULT 0;

-- The sweep asks: flagged critical, already notified, still not
-- acknowledged, and either never escalated or last escalated before the
-- cutoff. Keep that a cheap partial scan as the signed archive grows.
CREATE INDEX IF NOT EXISTS idx_imaging_report_critical_escalation
    ON clinical.imaging_reports (critical_escalated_at, critical_notified_at)
    WHERE critical_result_flagged_at IS NOT NULL
      AND critical_result_acknowledged_at IS NULL;
