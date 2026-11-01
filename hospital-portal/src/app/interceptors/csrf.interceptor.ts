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
  HttpContext,
  HttpContextToken,
  HttpErrorResponse,
  HttpEvent,
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

/**
 * QA D1145: marks the one replay this interceptor makes after a CSRF refusal.
 * The error interceptor runs INSIDE this one, so it saw the first refusal
 * before the replay existed and sent the page to /error/403 — a billing user
 * who may search invoices read "Acceso denegado" while the replay succeeded
 * behind it. It now stays silent on a first CSRF refusal and reports only the
 * replay's.
 */
export const CSRF_REPLAY = new HttpContextToken<boolean>(() => false);

/** The server's answer to a missing or stale CSRF token (JsonAccessDeniedHandler). */
export function isCsrfRefusal(err: unknown): boolean {
  return (
    err instanceof HttpErrorResponse &&
    err.status === 403 &&
    (err.error as { code?: string } | null)?.code === 'CSRF_TOKEN_INVALID'
  );
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
    return sendWithRetry(req, next, token, http);
  }

  // Cookie is absent (fresh session, hard reload, cookie cleared).
  // Self-heal: GET the bootstrap endpoint to force Spring to issue the cookie,
  // then replay the original mutating request with the fresh token.
  return bootstrapOnce(http).pipe(
    switchMap(() => {
      const freshToken = getCookieValue('XSRF-TOKEN');
      if (freshToken) {
        return sendWithRetry(req, next, freshToken, http);
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
 * QA D1145 (round 21): how many times a request refused for its CSRF token is
 * replayed. One was not enough after a language switch (a full reload fires
 * several writes at once): a sibling request sent while the stale cookie was
 * being dropped is refused WITHOUT a cookie, and that refusal's own Set-Cookie
 * lands after the fresh bootstrap token, so the single replay carried a token
 * the cookie no longer matched, and the billing search came back empty.
 */
export const MAX_CSRF_REPLAYS = 2;

/** Which replay this request is (0 = the original send). */
const CSRF_ATTEMPT = new HttpContextToken<number>(() => 0);

/**
 * Sends with the token; on a 403, when the cookie has changed since the token
 * was read (another response rotated it), replays with the current one.
 * QA D1145: when the server says the token itself is refused
 * (CSRF_TOKEN_INVALID) and the cookie has not changed, the cookie is stale:
 * it is dropped, a fresh one is fetched, and the request is replayed. At most
 * MAX_CSRF_REPLAYS replays; only the last is marked CSRF_REPLAY, so the error
 * interceptor reports the refusal once, when nothing is left to try. Any
 * other 403 is a real refusal and is passed on.
 */
function sendWithRetry(
  req: HttpRequest<unknown>,
  next: Parameters<HttpInterceptorFn>[1],
  token: string,
  http: HttpClient,
): Observable<HttpEvent<unknown>> {
  const attempt = req.context.get(CSRF_ATTEMPT);
  const replay = (fresh: string) => {
    const nextAttempt = attempt + 1;
    // A copy: the request already sent (and seen by the error interceptor) keeps its own context.
    const context = new HttpContext();
    for (const key of req.context.keys()) context.set(key, req.context.get(key));
    context.set(CSRF_ATTEMPT, nextAttempt).set(CSRF_REPLAY, nextAttempt === MAX_CSRF_REPLAYS);
    return sendWithRetry(req.clone({ context }), next, fresh, http);
  };
  return next(req.clone({ setHeaders: { 'X-XSRF-TOKEN': token } })).pipe(
    catchError((err: unknown) => {
      if (!(err instanceof HttpErrorResponse) || err.status !== 403) {
        return throwError(() => err);
      }
      if (attempt >= MAX_CSRF_REPLAYS) return throwError(() => err);
      const current = getCookieValue('XSRF-TOKEN');
      if (current && current !== token) {
        return replay(current);
      }
      if (isCsrfRefusal(err)) {
        expireCsrfCookie();
        return bootstrapOnce(http).pipe(
          switchMap(() => {
            const fresh = getCookieValue('XSRF-TOKEN');
            return fresh ? replay(fresh) : throwError(() => err);
          }),
        );
      }
      return throwError(() => err);
    }),
  );
}

/** Drop the stale XSRF-TOKEN cookie so the bootstrap GET is issued a new one. */
function expireCsrfCookie(): void {
  if (typeof document === 'undefined') return;
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/';
}

/** Test seam: forget a shared bootstrap between specs. */
export function resetCsrfBootstrapForTests(): void {
  inflightBootstrap = null;
}
