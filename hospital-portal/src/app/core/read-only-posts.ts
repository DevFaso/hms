/**
 * The POSTs that only read: searches and lookups whose terms (a name, a phone number, an
 * e-mail address, an MRN, a filter) travel in the body so they never reach a URL, and a few
 * evaluations that store nothing. The backend marks each handler `@ReadsOnly`.
 *
 * The portal treats them as reads wherever it tells a read from a write: a timeout says the
 * read timed out (not that the action may have been saved), no chart is reported changed, and a
 * refusal on a landing page is handled like a refused GET.
 *
 * Kept equal to the backend's `@ReadsOnly` POST routes by `scripts/read-only-posts.test.mjs`.
 * Routes as the backend maps them, without the `/api` prefix; `{name}` is one path segment.
 */
export const READ_ONLY_POST_ROUTES: readonly string[] = [
  '/appointments/patients/by-username',
  '/appointments/search',
  '/appointments/term-search',
  '/assignments/term-search',
  '/audit-logs/term-search',
  '/billing-invoices/search',
  '/cds-services/{serviceId}',
  '/chat/history/{user1Id}/{user2Id}/search',
  '/chat/messages/by-recipient-email',
  '/chat/messages/by-recipient-username',
  '/chat/messages/by-sender-email',
  '/chat/messages/by-sender-username',
  '/departments/filter',
  '/departments/search',
  '/empi/candidates',
  '/encounters/by-doctor',
  '/lab/inventory/hospital/{hospitalId}/search',
  '/lookup/appointment/patient/email',
  '/lookup/appointment/patient/mrn',
  '/lookup/appointment/patient/phone',
  '/lookup/appointment/staff/email',
  '/me/patient/notifications/term-search',
  '/notifications/term-search',
  '/patients/lookup',
  '/patients/national-unique-id/check',
  '/patients/national-unique-id/search',
  '/patients/national-unique-id/{patientId}/reveal',
  '/patients/registration-match',
  '/patients/search',
  '/patients/term-search',
  '/patients/{id}/doctor-record',
  '/patients/{id}/doctor-timeline',
  '/pharmacy/dispense/work-queue/term-search',
  '/prenatal/schedule',
  '/prescriptions/term-search',
  '/reception/patients/duplicate-candidates',
  '/signatures/verify',
  '/staff/search/by-email',
  '/staff/search/by-phone',
  '/super-admin/audit-search',
  '/super-admin/audit-search/csv',
  '/super-admin/organizations/hierarchy/term-search',
  '/super-admin/security/rules/simulations',
  '/users/search',
];

function routePattern(route: string): RegExp {
  const source = route
    .split('/')
    .map((segment) =>
      /^\{[^}]+\}$/.test(segment) ? '[^/]+' : segment.replaceAll(/[.*+?^${}()|[\]\\]/g, '\\$&'),
    )
    .join('/');
  return new RegExp(`^${source}/?$`);
}

const PATTERNS = READ_ONLY_POST_ROUTES.map(routePattern);

/** The request's path as the backend maps it: no origin, no `/api`, no query or fragment. */
function routeOf(url: string): string {
  const path = url.replace(/^[a-z][a-z0-9+.-]*:\/\/[^/]*/i, '').replace(/[?#].*$/, '');
  const rooted = path.startsWith('/') ? path : `/${path}`;
  return rooted.replace(/^\/api(?=\/|$)/, '');
}

/** True for a POST that only reads (see {@link READ_ONLY_POST_ROUTES}). */
export function isReadOnlyPost(method: string, url: string): boolean {
  if (method.toUpperCase() !== 'POST') return false;
  const route = routeOf(url);
  return PATTERNS.some((pattern) => pattern.test(route));
}

/** True for a request that only reads: a GET, a HEAD, an OPTIONS, or a read-only POST. */
export function isReadRequest(method: string, url: string): boolean {
  return ['GET', 'HEAD', 'OPTIONS'].includes(method.toUpperCase()) || isReadOnlyPost(method, url);
}
