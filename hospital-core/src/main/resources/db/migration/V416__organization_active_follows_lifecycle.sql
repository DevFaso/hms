-- V416: an organization's `active` flag agrees with its lifecycle state where
-- that cannot change anyone's access (QA r81 D4317).
--
-- The organization list's "deactivate" used to clear `active` and leave
-- lifecycle_state = 'ACTIVE'. Access is gated by the lifecycle state alone, so
-- the flag is what is corrected, and only for an organization that is visibly
-- in use: one that still holds an active facility goes back to active.
--
-- Nothing is suspended here. An inactive organization with no active facility
-- may still have users assigned at one of its inactive facilities, and a
-- suspended organization blocks every request of its users: those rows are
-- left exactly as they are, for a person to suspend from the lifecycle page.
--
-- Idempotent: a second run finds no such row. No rollback declared.

UPDATE hospital.organizations o
   SET active = TRUE
 WHERE o.active = FALSE
   AND o.lifecycle_state = 'ACTIVE'
   AND EXISTS (SELECT 1 FROM hospital.hospitals h
                WHERE h.organization_id = o.id AND h.active = TRUE);
