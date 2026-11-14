import { HttpContext, HttpContextToken } from '@angular/common/http';

/**
 * PR #1163 (user decision 2026-11-14): a super-admin's « Tous les hôpitaux »
 * read. The auth interceptor sends no `X-Hospital-Id` on a request carrying
 * it, so the server answers across every hospital (global view) even when the
 * chip pins one. Honoured for a super-admin only; anyone else keeps their
 * hospital header, and the server would refuse a global view to them anyway.
 */
export const ALL_HOSPITALS_REQUEST = new HttpContextToken<boolean>(() => false);

export function allHospitalsRequestOptions(): { context: HttpContext } {
  return { context: new HttpContext().set(ALL_HOSPITALS_REQUEST, true) };
}
