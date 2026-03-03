import { HttpEvent, HttpHandlerFn, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { EMPTY, Observable } from 'rxjs';

import { environment } from '../../environments/environment';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';

/**
 * Handle an expired access token before the request is sent.
 *
 * - No refresh token available → logout immediately and cancel the request.
 * - Refresh token available → let the request go through (the error interceptor
 *   will catch the 401 and transparently refresh + retry).
 *
 * Returns an Observable when the caller should return that observable instead of
 * proceeding with normal token attachment.
 */
function handleExpiredToken(
  auth: AuthService,
  router: Router,
  modified: HttpRequest<unknown>,
  next: HttpHandlerFn,
): Observable<HttpEvent<unknown>> {
  if (!auth.getRefreshToken()) {
    auth.logout();
    void router.navigate(['/login']);
    return EMPTY;
  }
  // Refresh token exists — send the request as-is (without attaching the
  // expired bearer). The error interceptor will handle the 401 refresh cycle.
  return next(modified);
}

export const apiPrefixInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const roleCtx = inject(RoleContextService);
  const router = inject(Router);

  // Skip absolute URLs and i18n assets
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

  // Add Authorization header for API requests (skip auth endpoints)
  if (!/\/auth\/login(?:[/?#]|$)/i.test(modified.url)) {
    const token = auth.getToken();

    if (token && auth.isExpired(token)) {
      return handleExpiredToken(auth, router, modified, next);
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
