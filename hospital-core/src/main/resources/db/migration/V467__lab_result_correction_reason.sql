-- V467: why a released lab result was corrected (QA D4143, r81).
--
-- A correction of a released result is a new result that supersedes the first. It carried no reason: the
-- culture report and the blood grouping both ask for one. The reason is typed by whoever enters the
-- correction and is shown to the clinician beside the value it replaced.
--
-- Additive and idempotent. NULL on every result that corrects nothing, and on a correction entered before
-- this migration. No automated rollback (forward-only).

ALTER TABLE lab.lab_results ADD COLUMN IF NOT EXISTS correction_reason varchar(500);
