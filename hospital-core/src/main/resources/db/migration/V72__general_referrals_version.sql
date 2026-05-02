-- V72: P1 #12 follow-up — JPA optimistic-lock version on general_referrals
--
-- Required by the EXPIRED auto-sweep: without @Version, a concurrent
-- transaction that flips status between the sweep's SELECT and its UPDATE
-- would be silently overwritten (lost update). With @Version, the second
-- writer's flush throws ObjectOptimisticLockingFailureException and the
-- sweep skips the row.
--
-- Default 0 backfills existing rows; @Version takes over for new ones.
-- Additive only; pure DDL.

ALTER TABLE public.general_referrals
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Rollback (manual only):
--   ALTER TABLE public.general_referrals DROP COLUMN IF EXISTS version;
