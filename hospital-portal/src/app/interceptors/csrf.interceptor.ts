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
 * References:
 *   OWASP CSRF Prevention Cheat Sheet
 *   Spring Security Reference – CSRF / Double-Submit Cookie Pattern
 *   CWE-352
 */

import { HttpInterceptorFn, HttpRequest } from '@angular/common/http';

/** HTTP methods that change server state and therefore require a CSRF token. */
const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

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

export const csrfInterceptor: HttpInterceptorFn = (req: HttpRequest<unknown>, next) => {
  // Only inject the header for mutating methods targeting our own API.
  if (!MUTATING_METHODS.has(req.method)) {
    return next(req);
  }

  const token = getCookieValue('XSRF-TOKEN');
  if (!token) {
    return next(req);
  }

  // Clone and attach the token — never send it on cross-origin requests.
  const secured = req.clone({
    setHeaders: { 'X-XSRF-TOKEN': token },
  });

  return next(secured);
};
