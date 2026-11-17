-- V178: pharmacy "ready for collection" (audit gap G15).
--
-- A hospital fill can now be PREPARED first (stock set aside, the patient
-- told it is waiting) and HANDED OVER later. The prepared fill is a
-- clinical.dispenses row in status PENDING, a value no server code wrote
-- before this change. Plan: docs/plan/pharmacy-ready-for-collection-plan.md.
--
-- 1. Legacy client-written PENDING rows become COMPLETED. Old code counted
--    them as fills, so the accounting does not change. Verified 2026-10-07:
--    dev and prod clinical.dispenses hold 0 rows, so this is a no-op there;
--    it stays for any other environment.
-- 2. dispensed_at is nullable: a prepared fill has not been handed over.
--    The KPI paths already filter dispensed_at IS NOT NULL.
-- 3. Three nullable columns: prepared_by (who prepared the fill; kept after
--    hand-over), ready_reminder_sent_at (the one-reminder claim stamp) and
--    cancel_reason (why a preparation was cancelled or voided).
-- 4. One open preparation per prescription: a partial UNIQUE index. It is
--    NOT declared as a JPA @Index: H2 builds tables from the entities and
--    would create a FULL unique index on prescription_id.
-- 5. A partial index on created_at for the daily reminder sweep.
--
-- No CHECK on status (there has never been one). Forward-only; harmless to
-- the previous application version.

UPDATE clinical.dispenses SET status = 'COMPLETED' WHERE status = 'PENDING';

ALTER TABLE clinical.dispenses ALTER COLUMN dispensed_at DROP NOT NULL;

ALTER TABLE clinical.dispenses
    ADD COLUMN IF NOT EXISTS prepared_by UUID NULL
        CONSTRAINT fk_disp_prepared_by REFERENCES "security".users(id);

ALTER TABLE clinical.dispenses
    ADD COLUMN IF NOT EXISTS ready_reminder_sent_at TIMESTAMP NULL;

ALTER TABLE clinical.dispenses
    ADD COLUMN IF NOT EXISTS cancel_reason VARCHAR(40) NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_disp_one_pending_per_rx
    ON clinical.dispenses (prescription_id)
 WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_disp_pending_created
    ON clinical.dispenses (created_at)
 WHERE status = 'PENDING';
