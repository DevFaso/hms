-- V77: Tenant lifecycle on Organization (MVP-2 — gap #2 in docs/super-admin-gaps.md)
--
-- Adds a lifecycle state machine to the organizations table so a super admin can
-- suspend, archive, restore, and schedule-purge a tenant. All transitions are
-- additionally captured in audit_event_log; the columns below are the entity-side
-- snapshot of the most recent transition of each kind.
--
-- Strictly additive (no DROP / no NOT NULL on existing rows without a default).
-- Existing rows are backfilled to lifecycle_state = 'ACTIVE'.
--
-- Rollback plan: dropping the columns is safe — `lifecycle_state` has a default
-- so removal does not break inserts elsewhere; the JPA mapping will compile
-- even if the columns are absent in older snapshots, but production rollback
-- requires shipping the previous Organization.java alongside the schema rollback.

ALTER TABLE hospital.organizations
    ADD COLUMN IF NOT EXISTS lifecycle_state      VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS suspended_at         TIMESTAMP   NULL,
    ADD COLUMN IF NOT EXISTS suspended_by         UUID        NULL,
    ADD COLUMN IF NOT EXISTS suspension_reason    VARCHAR(1000) NULL,
    ADD COLUMN IF NOT EXISTS archived_at          TIMESTAMP   NULL,
    ADD COLUMN IF NOT EXISTS archived_by          UUID        NULL,
    ADD COLUMN IF NOT EXISTS archive_reason       VARCHAR(1000) NULL,
    ADD COLUMN IF NOT EXISTS purge_scheduled_for  TIMESTAMP   NULL,
    ADD COLUMN IF NOT EXISTS purge_scheduled_by   UUID        NULL,
    ADD COLUMN IF NOT EXISTS purge_reason         VARCHAR(1000) NULL,
    ADD COLUMN IF NOT EXISTS purged_at            TIMESTAMP   NULL;

-- Backfill any pre-existing rows that may have NULL lifecycle_state if the
-- DEFAULT clause was not applied by the engine (defensive — matches the JPA
-- expectation that lifecycle_state is never null).
UPDATE hospital.organizations
   SET lifecycle_state = 'ACTIVE'
 WHERE lifecycle_state IS NULL;

CREATE INDEX IF NOT EXISTS idx_organization_lifecycle
    ON hospital.organizations (lifecycle_state);

-- Partial index to make the nightly purge sweep cheap: only PENDING_PURGE rows
-- are scanned, ordered by scheduled time.
CREATE INDEX IF NOT EXISTS idx_organization_purge_pending
    ON hospital.organizations (purge_scheduled_for)
 WHERE lifecycle_state = 'PENDING_PURGE';
