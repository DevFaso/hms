-- V416: an organization's `active` flag and its lifecycle state agree
-- (QA r81 D4317).
--
-- The organization list's "deactivate" used to clear `active` and leave
-- lifecycle_state = 'ACTIVE'; the hospital form's switch did the same. From
-- this release both switches move together. This aligns the rows written
-- before, without changing who can sign in (the lifecycle state is what
-- gates access, so it is the flag that is corrected wherever the facility or
-- its organization is still in use):
--   * an inactive organization in lifecycle ACTIVE that still holds an active
--     facility is in use: its flag goes back to active;
--   * one with no active facility becomes SUSPENDED, as a deactivation now does.
-- Hospital rows are left as they are: the form applies the rule from now on.
--
-- Idempotent: a second run finds no such row. No rollback declared.

UPDATE hospital.organizations o
   SET active = TRUE
 WHERE o.active = FALSE
   AND o.lifecycle_state = 'ACTIVE'
   AND EXISTS (SELECT 1 FROM hospital.hospitals h
                WHERE h.organization_id = o.id AND h.active = TRUE);

UPDATE hospital.organizations o
   SET lifecycle_state = 'SUSPENDED',
       suspended_at = COALESCE(o.suspended_at, NOW()),
       suspension_reason = COALESCE(o.suspension_reason, 'ORGANIZATION_DEACTIVATED')
 WHERE o.active = FALSE
   AND o.lifecycle_state = 'ACTIVE';
