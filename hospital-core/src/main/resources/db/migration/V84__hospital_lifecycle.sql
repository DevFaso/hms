-- V84: Hospital-level lifecycle (MVP-c batch — Hospital lifecycle item
-- in docs/super-admin-gaps.md "MVP-c batch" section).
--
-- Mirrors V77's Organization lifecycle columns onto hospital.hospitals
-- so a super admin can suspend / archive / restore / schedule-purge an
-- individual hospital independently of its parent organization.
-- Suspending an organization implicitly blocks login at every hospital
-- under it (handled in JwtAuthenticationFilter); a hospital lifecycle
-- transition narrows the block to a single facility without touching
-- siblings.
--
-- Strictly additive — every column has a default or is nullable; the
-- existing `active` column is preserved (kept as the soft-delete flag
-- it always was). Rollback drops the new columns; the JPA mapping
-- tolerates absent columns at read time as long as the Hospital.java
-- shipped with the rollback omits them too.

ALTER TABLE hospital.hospitals
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

UPDATE hospital.hospitals
   SET lifecycle_state = 'ACTIVE'
 WHERE lifecycle_state IS NULL;

CREATE INDEX IF NOT EXISTS idx_hospital_lifecycle
    ON hospital.hospitals (lifecycle_state);

CREATE INDEX IF NOT EXISTS idx_hospital_purge_pending
    ON hospital.hospitals (purge_scheduled_for)
 WHERE lifecycle_state = 'PENDING_PURGE';
