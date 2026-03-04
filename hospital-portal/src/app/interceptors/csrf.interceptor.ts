/**
 * CSRF Double-Submit Cookie interceptor.
 *
 * Spring Security writes an `XSRF-TOKEN` cookie (HttpOnly=false) on the
 * first response. This interceptor reads that cookie and echoes its value
 * back as the `X-XSRF-TOKEN` request header on every state-mutating
 * request (POST, PUT, PATCH, DELETE).
 *
 * This satisfies the Double-Submit Cookie pattern recommended by OWASP and
 * required by the Spring CookieCsrfTokenRepository / CsrfTokenRequestAttributeHandler
 * combination configured in SecurityConfig.
 *
 * Self-healing bootstrap:
 *   If the `XSRF-TOKEN` cookie is absent when a mutating request fires (e.g.
 *   the user deep-linked to a page that immediately issues a PUT without a prior
 *   GET, or the cookie was cleared), this interceptor transparently GETs
 *   `/api/auth/csrf-token` to force Spring to set the cookie, waits for the
 *   response, then replays the original request with the freshly-issued token.
 *   This means the very first PUT/POST on a cold session still works correctly.
 *
 * References:
 *   OWASP CSRF Prevention Cheat Sheet
 *   Spring Security Reference – CSRF / Double-Submit Cookie Pattern
 *   CWE-352
 */

import { HttpClient, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, of } from 'rxjs';

/** HTTP methods that change server state and therefore require a CSRF token. */
const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/**
 * The bootstrap endpoint that Spring uses to issue the XSRF-TOKEN cookie.
 * It must be a GET so Spring sets the cookie in the response.
 * Uses a relative URL so it goes through apiPrefixInterceptor normally.
 */
const CSRF_BOOTSTRAP_URL = '/auth/csrf-token';

/**
 * Read a cookie value by name from document.cookie.
 * Returns null when running in SSR or when the cookie is absent.
 */
function getCookieValue(name: string): string | null {
  if (typeof document === 'undefined') return null;
  const match = document.cookie
    .split(';')
    .map((c) => c.trim())
    .find((c) => c.startsWith(`${name}=`));
  return match ? decodeURIComponent(match.slice(name.length + 1)) : null;
}

/**
 * Returns true when the request is for the CSRF bootstrap endpoint itself,
 * to prevent infinite recursion (that GET should never be intercepted here).
 */
function isCsrfBootstrapRequest(url: string): boolean {
  return url.includes('/auth/csrf-token');
}

/**
 * Returns true when the request is cross-origin, in which case we must NOT
 * attach or bootstrap the CSRF token.
 */
function isCrossOrigin(url: string): boolean {
  if (!url.startsWith('http://') && !url.startsWith('https://')) {
    return false; // relative URL → same-origin by definition
  }
  try {
    const reqOrigin = new URL(url).origin;
    return globalThis.window !== undefined && reqOrigin !== globalThis.window.location.origin;
  } catch {
    return true; // malformed absolute URL → treat as cross-origin (safe default)
  }
}

export const csrfInterceptor: HttpInterceptorFn = (req: HttpRequest<unknown>, next) => {
  // Only inject the CSRF header for state-mutating methods.
  if (!MUTATING_METHODS.has(req.method)) {
    return next(req);
  }

  // Never attach the CSRF token to cross-origin requests.
  if (isCrossOrigin(req.url)) {
    return next(req);
  }

  // Never intercept the bootstrap request itself (would cause infinite recursion).
  if (isCsrfBootstrapRequest(req.url)) {
    return next(req);
  }

  const token = getCookieValue('XSRF-TOKEN');

  if (token) {
    // Happy path: cookie already present — attach it and proceed immediately.
    return next(req.clone({ setHeaders: { 'X-XSRF-TOKEN': token } }));
  }

  // Cookie is absent (fresh session, hard reload, cookie cleared).
  // Self-heal: GET the bootstrap endpoint to force Spring to issue the cookie,
  // then replay the original mutating request with the fresh token.
  const http = inject(HttpClient);

  return http.get<void>(CSRF_BOOTSTRAP_URL, { observe: 'response' }).pipe(
    catchError(() => of(null)), // if bootstrap fails, proceed anyway (server will 403 → handled upstream)
    switchMap(() => {
      const freshToken = getCookieValue('XSRF-TOKEN');
      if (freshToken) {
        return next(req.clone({ setHeaders: { 'X-XSRF-TOKEN': freshToken } }));
      }
      // Bootstrap didn't set the cookie (network error, misconfigured backend).
      // Send the request without the CSRF header — the server will reject it
      // with 403 which the error interceptor will surface to the user.
      return next(req);
    }),
  );
};
