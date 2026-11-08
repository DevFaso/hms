/**
 * QA D1012 (class guard): every request the shell and the app-wide services
 * it starts send on a page load, with the roles the server admits for it.
 *
 * `ANY_AUTHENTICATED` means the endpoint refuses no signed-in role. A list
 * means the server answers only those roles, so the portal must not ask for
 * anyone else: the D987 duplicate-patient probe was sent for every role, and
 * every patient, pharmacist and lab user got a 403 on every page load (and a
 * second 403 from the audit sink it reported to).
 *
 * Two tests hold this table:
 * - `shell-startup-endpoints.spec.ts` boots the real shell for every role and
 *   fails on a request that is not listed here, or that a listed role list
 *   does not admit for the role booted.
 * - `ShellStartupEndpointRolesTest` (hospital-core) resolves each entry to the
 *   controller method that serves it and fails when its `@PreAuthorize` (or
 *   the lack of one) says something else.
 */
export const ANY_AUTHENTICATED = 'ANY_AUTHENTICATED';

export const SHELL_STARTUP_ENDPOINTS: Readonly<
  Record<string, typeof ANY_AUTHENTICATED | readonly string[]>
> = {
  'GET /me/dashboard-config': ANY_AUTHENTICATED,
  'GET /notifications': ANY_AUTHENTICATED,
  'GET /chat/unread-count': ANY_AUTHENTICATED,
  'GET /super-admin/impersonation/active': ANY_AUTHENTICATED,
  'GET /downtime/status': ANY_AUTHENTICATED,
  'GET /empi/candidates/status': [
    'ROLE_RECEPTIONIST',
    'ROLE_NURSE',
    'ROLE_DOCTOR',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_SUPER_ADMIN',
  ],
};

/**
 * Every role the backend declares (`SecurityConstants`); the Java test fails
 * when a role is added there and not here, so a new role is booted too.
 */
export const SHELL_GUARD_ROLES: readonly string[] = [
  'ROLE_SUPER_ADMIN',
  'ROLE_HOSPITAL_ADMIN',
  'ROLE_DOCTOR',
  'ROLE_NURSE',
  'ROLE_PATIENT',
  'ROLE_RECEPTIONIST',
  'ROLE_STAFF',
  'ROLE_LAB_SCIENTIST',
  'ROLE_LAB_DIRECTOR',
  'ROLE_QUALITY_MANAGER',
  'ROLE_PHARMACIST',
  'ROLE_LAB_TECHNICIAN',
  'ROLE_LAB_MANAGER',
  'ROLE_ACCOUNTANT',
  'ROLE_ADMIN',
  'ROLE_RADIOLOGIST',
  'ROLE_SURGEON',
  'ROLE_ANESTHESIOLOGIST',
  'ROLE_PHYSIOTHERAPIST',
  'ROLE_MIDWIFE',
  'ROLE_BILLING_SPECIALIST',
  'ROLE_PHARMACY_VERIFIER',
  'ROLE_INVENTORY_CLERK',
  'ROLE_STORE_MANAGER',
  'ROLE_CLAIMS_REVIEWER',
];
