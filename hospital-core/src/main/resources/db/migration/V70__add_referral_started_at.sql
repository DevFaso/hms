-- V70: Add started_at to general_referrals for IN_PROGRESS lifecycle transition.
-- Part of P1 #12 Referral Lifecycle (close-out: SCHEDULED, IN_PROGRESS, REJECTED).
--
-- Rollback: ALTER TABLE general_referrals DROP COLUMN IF EXISTS started_at;

ALTER TABLE general_referrals
    ADD COLUMN IF NOT EXISTS started_at TIMESTAMP NULL;
