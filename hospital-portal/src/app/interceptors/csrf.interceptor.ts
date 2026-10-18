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

import {
  HttpClient,
  HttpErrorResponse,
  HttpInterceptorFn,
  HttpRequest,
} from '@angular/common/http';
import { inject } from '@angular/core';
import {
  Observable,
  catchError,
  finalize,
  map,
  of,
  shareReplay,
  switchMap,
  throwError,
} from 'rxjs';

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

  const http = inject(HttpClient);
  const token = getCookieValue('XSRF-TOKEN');
  if (token) {
    return sendWithRetry(req, next, token);
  }

  // Cookie is absent (fresh session, hard reload, cookie cleared).
  // Self-heal: GET the bootstrap endpoint to force Spring to issue the cookie,
  // then replay the original mutating request with the fresh token.
  return bootstrapOnce(http).pipe(
    switchMap(() => {
      const freshToken = getCookieValue('XSRF-TOKEN');
      if (freshToken) {
        return sendWithRetry(req, next, freshToken);
      }
      // Bootstrap didn't set the cookie (network error, misconfigured backend).
      // Send the request without the CSRF header — the server will reject it
      // with 403 which the error interceptor will surface to the user.
      return next(req);
    }),
  );
};

/**
 * QA D180: the first page after login fires several mutating requests at
 * once (the billing search is a POST). Each used to start its own bootstrap
 * GET, and every bootstrap response set a NEW cookie: a request that read
 * the cookie before the last response landed sent a token the cookie no
 * longer matched, and got 403 "sometimes". One bootstrap is now shared by
 * every request waiting on it.
 */
let inflightBootstrap: Observable<void> | null = null;

function bootstrapOnce(http: HttpClient): Observable<void> {
  inflightBootstrap ??= http.get<void>(CSRF_BOOTSTRAP_URL, { observe: 'response' }).pipe(
    map(() => undefined),
    catchError(() => of(undefined)), // if bootstrap fails, proceed anyway (server will 403 → handled upstream)
    finalize(() => {
      inflightBootstrap = null;
    }),
    shareReplay({ bufferSize: 1, refCount: false }),
  );
  return inflightBootstrap;
}

/**
 * Sends with the token; on a 403, when the cookie has changed since the token
 * was read (another response rotated it), replays once with the current one.
 * A 403 with an unchanged cookie is a real refusal and is passed on.
 */
function sendWithRetry(
  req: HttpRequest<unknown>,
  next: Parameters<HttpInterceptorFn>[1],
  token: string,
) {
  return next(req.clone({ setHeaders: { 'X-XSRF-TOKEN': token } })).pipe(
    catchError((err: unknown) => {
      if (!(err instanceof HttpErrorResponse) || err.status !== 403) {
        return throwError(() => err);
      }
      const current = getCookieValue('XSRF-TOKEN');
      if (current && current !== token) {
        return next(req.clone({ setHeaders: { 'X-XSRF-TOKEN': current } }));
      }
      return throwError(() => err);
    }),
  );
}

/** Test seam: forget a shared bootstrap between specs. */
export function resetCsrfBootstrapForTests(): void {
  inflightBootstrap = null;
}
