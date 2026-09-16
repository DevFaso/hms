/**
 * The literal `AuditEventLogServiceImpl` stamps when it cannot resolve a role.
 * It is a sentence, not a token, so no key can cover it.
 */
const UNKNOWN_ROLE = 'Unknown Role';
const ROLE_PREFIX = 'ROLE_';

/**
 * One role token in the bare form `PORTAL.ENUM.ROLE` keys.
 *
 * `security.roles.name` carries the prefix (`ROLE_DOCTOR`), and the two
 * writers of `audit_event_logs.role_name` disagree about it:
 * `WriteAuditInterceptor` strips it deliberately — its own javadoc says it
 * does so "so one actor's rows group under one role name" — while
 * `AuditEventLogServiceImpl.resolveRoleName` stores whatever the caller
 * passed, which is `assignment.getRole().getName()` with the prefix on. Both
 * spellings are in the column on every environment, and rows written years
 * ago keep theirs, so normalising in the portal rather than in the backend is
 * not a workaround — there is no single backend write to fix.
 *
 * Returns null for a blank value and for the `Unknown Role` sentence, so the
 * caller can hide the chip instead of rendering an English placeholder.
 *
 * Lives in `core/` rather than beside one feature service because every
 * surface that shows a role needs it: the staff pickers, the user registration
 * form, the shell, the login role cards and the patient's access log.
 */
export function bareRole(raw: string | null | undefined): string | null {
  const value = raw?.trim();
  if (!value || value === UNKNOWN_ROLE) return null;
  return value.startsWith(ROLE_PREFIX) ? value.slice(ROLE_PREFIX.length) : value;
}
