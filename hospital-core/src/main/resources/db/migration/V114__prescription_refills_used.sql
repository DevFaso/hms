-- =====================================================================
-- V114 — Track how many refills of a prescription have been authorized.
--
-- `refills_allowed` and `refills_remaining` have existed since V1 and were
-- never read by anything: no service decremented them, no DTO carried them,
-- and no UI displayed them. Approving a refill request wrote APPROVED and
-- stopped there, so an approved refill produced nothing the pharmacy could
-- dispense against.
--
-- `refills_used` is the missing piece for the fill accounting.
-- DispenseServiceImpl decides PARTIALLY_FILLED vs DISPENSED by comparing the
-- lifetime sum of dispensed quantity against the prescription's `quantity`.
-- Once a prescription is refilled that comparison is wrong — the lifetime sum
-- already covers the first fill, so any partial second fill would report as
-- fully DISPENSED. Expected total becomes quantity * (1 + refills_used).
--
-- Idempotent per the V108 deployment lesson: a deploy killed mid-Liquibase
-- must be safe to replay.
-- =====================================================================

ALTER TABLE clinical.prescriptions
    ADD COLUMN IF NOT EXISTS refills_used INTEGER NOT NULL DEFAULT 0;

-- Existing rows have never been refilled, so the DEFAULT 0 above is already
-- the correct backfill. Stated explicitly rather than left implicit.
UPDATE clinical.prescriptions SET refills_used = 0 WHERE refills_used IS NULL;
