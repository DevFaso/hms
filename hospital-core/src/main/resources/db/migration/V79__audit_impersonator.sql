-- V79: Impersonator stamp on audit events (MVP-4 — gap #4 in docs/super-admin-gaps.md)
--
-- Adds two columns to support.audit_event_logs so that any action a super
-- admin performs while impersonating another user can still be traced back
-- to the real human behind the call. The IMPERSONATION_STARTED /
-- IMPERSONATION_ENDED boundary events get logged with the impersonator as
-- the primary user. For every action under the impersonation token, the
-- AuditEventLogServiceImpl reads the impersonator JWT claims off the
-- request-scoped ImpersonationContext and stamps these columns.
--
-- Strictly additive — no DROP, both columns NULL-able. Existing rows stay
-- at NULL (no impersonator), which matches the pre-MVP-4 behaviour.
--
-- Rollback plan: dropping the columns is safe — no FK, no other table
-- references them, and the AuditEventLogServiceImpl null-checks before
-- setting either field.

ALTER TABLE support.audit_event_logs
    ADD COLUMN IF NOT EXISTS impersonator_user_id   UUID         NULL,
    ADD COLUMN IF NOT EXISTS impersonator_username  VARCHAR(255) NULL;

-- Compliance / forensic queries: "every action user X took *while being
-- impersonated by* anyone, in date order". Partial index keeps the index
-- size bounded — most rows will not have an impersonator.
CREATE INDEX IF NOT EXISTS idx_audit_impersonator_user_event_ts
    ON support.audit_event_logs (impersonator_user_id, event_timestamp DESC)
 WHERE impersonator_user_id IS NOT NULL;
