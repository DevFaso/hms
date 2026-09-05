-- V155: ShedLock table — one instance runs each scheduled sweep.
--
-- WHY: 26 @Scheduled jobs and no lock anywhere. Railway restarts on
-- failure and a second instance during a deploy overlap is enough for two
-- copies of the same sweep to run at once: two reminder sweeps stamping
-- and messaging the same appointment, two outbox dispatchers claiming the
-- same rows, two KPI refreshes contending for the same materialized view.
-- ShedLock's JDBC provider takes a row-level lock in this table before a
-- job runs (name = the job, lock_until = when a stale lock may be taken
-- over); the other instance sees the row and skips.
--
-- Standard ShedLock layout (name/lock_until/locked_at/locked_by). Lives in
-- platform with the other cross-cutting tables. Idempotent.
--
-- Rollback:
--   DROP TABLE IF EXISTS platform.shedlock;
-- =============================================================================

CREATE TABLE IF NOT EXISTS platform.shedlock (
    name        VARCHAR(64)  NOT NULL,
    lock_until  TIMESTAMP    NOT NULL,
    locked_at   TIMESTAMP    NOT NULL,
    locked_by   VARCHAR(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);
