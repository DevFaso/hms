-- V188: a patient record is no longer switched off while its portal account
-- waits for activation (QA defect D27).
--
-- clinical.patients.is_active is the administrative soft-delete of the
-- record. Patient creation used to copy the ACCOUNT's pending state into it,
-- so every newly registered patient - and every patient with no e-mail or
-- phone who can never activate - was hidden from the clinical pickers
-- (/patients/search defaults to active=true) until they signed in. The copy
-- is removed in code; this puts back the records it switched off.
--
-- Only records whose account is exactly a pending one: inactive, not
-- deleted, never disabled by an administrator, never signed in. A record an
-- administrator deactivated on an account that has signed in is untouched.
--
-- Staff can also switch a record off through the patient-update path, which
-- never stamps users.deactivated_by_admin_at - so the account's state alone
-- cannot tell the copy-on-create switch-off from a deliberate one. The rule:
-- only records never updated after creation (updated_at within 5 seconds of
-- created_at) are put back. A record touched since - including a deliberate
-- deactivation - is left as it is; staff can re-activate it by hand.
-- Idempotent: a second run matches nothing.

UPDATE clinical.patients p
   SET is_active = TRUE
  FROM security.users u
 WHERE p.user_id = u.id
   AND p.is_active = FALSE
   AND u.is_active = FALSE
   AND u.is_deleted = FALSE
   AND u.deactivated_by_admin_at IS NULL
   AND u.last_login_at IS NULL
   AND p.updated_at IS NOT NULL
   AND p.created_at IS NOT NULL
   AND p.updated_at <= p.created_at + INTERVAL '5 seconds';
