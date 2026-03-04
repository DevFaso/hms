import { HttpEvent, HttpHandlerFn, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { EMPTY, Observable } from 'rxjs';

import { environment } from '../../environments/environment';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';

/**
 * Handle a proactively-detected expired access token before the request is sent.
 *
 * Strategy:
 * - No refresh token available → logout immediately and cancel the request.
 * - Refresh token IS available → attach the (expired) token as the Authorization
 *   header anyway and let the request proceed.  The server will return 401 and
 *   the errorInterceptor will transparently refresh + retry with a fresh token.
 *
 * Why attach an expired token instead of refreshing proactively here?
 *   1. The error interceptor already has the full refresh+queue+retry machinery.
 *      Duplicating that logic here would create two competing refresh paths.
 *   2. We must NOT send the request with no Authorization header: if we do, the
 *      server can't tell whether the caller forgot the header or the token simply
 *      expired — some backends return 400/403 instead of 401 in that case.
 *   3. The server's 401 is the canonical signal; reacting to it centrally in the
 *      error interceptor is the correct pattern for functional interceptors.
 */
function handleExpiredToken(
  auth: AuthService,
  router: Router,
  modified: HttpRequest<unknown>,
  next: HttpHandlerFn,
  expiredToken: string,
): Observable<HttpEvent<unknown>> {
  if (!auth.getRefreshToken()) {
    // No way to renew — hard logout.
    auth.logout();
    void router.navigate(['/login']);
    return EMPTY;
  }

  // Attach the expired token so the server issues a proper 401 and the
  // errorInterceptor can run its refresh+retry cycle for this request.
  const withExpiredBearer = modified.clone({
    setHeaders: { Authorization: `Bearer ${expiredToken}` },
  });
  return next(withExpiredBearer);
}

export const apiPrefixInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const roleCtx = inject(RoleContextService);
  const router = inject(Router);

  // Skip absolute URLs and i18n assets — they bypass all API logic.
  if (
    /^https?:\/\//i.test(req.url) ||
    /^assets\/i18n\//i.test(req.url) ||
    req.url.includes('assets/i18n/')
  ) {
    return next(req);
  }

  // Normalize path — prevent double /api/api prefix
  const path = /^\/?api\//i.test(req.url) ? req.url.replace(/^\/?api\//i, '/') : req.url;

  // Build final URL
  const finalUrl = `${environment.apiBase}${path.startsWith('/') ? path : '/' + path}`;

  let modified = req.clone({ url: finalUrl });

  // Attach auth headers for all API calls except the login endpoint itself.
  if (!/\/auth\/login(?:[/?#]|$)/i.test(modified.url)) {
    const token = auth.getToken();

    if (token && auth.isExpired(token)) {
      // Token is expired — delegate to the helper which either hard-logouts
      // (no refresh token) or sends the expired bearer so the errorInterceptor
      // can do the refresh+retry cycle.
      return handleExpiredToken(auth, router, modified, next, token);
    }

    const headers: Record<string, string> = {};
    if (token && !modified.headers.has('Authorization')) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    const hid = roleCtx.activeHospitalId;
    if (hid) {
      headers['X-Hospital-Id'] = hid;
    }
    if (Object.keys(headers).length) {
      modified = modified.clone({ setHeaders: headers });
    }
  }

  return next(modified);
};
