-- ─────────────────────────────────────────────────────────────────────────────
-- P0 #5: Critical-value escalation loop. Stamps on lab.lab_results tracking
-- when the ordering provider was notified of a critical result and when the
-- unacknowledged result was escalated, so the sweep never re-notifies.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE lab.lab_results ADD COLUMN IF NOT EXISTS critical_notified_at TIMESTAMP;
ALTER TABLE lab.lab_results ADD COLUMN IF NOT EXISTS critical_escalated_at TIMESTAMP;

-- Partial index sized for the sweep: unacknowledged, notified, not yet escalated.
CREATE INDEX IF NOT EXISTS idx_lab_results_critical_pending
    ON lab.lab_results (critical_notified_at)
    WHERE acknowledged = false AND critical_escalated_at IS NULL;
