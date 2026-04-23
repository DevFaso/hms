import { HttpEvent, HttpHandlerFn, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { EMPTY, Observable } from 'rxjs';

import { environment } from '../../environments/environment';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';

function handleExpiredToken(
  auth: AuthService,
  router: Router,
  modified: HttpRequest<unknown>,
  next: HttpHandlerFn,
  expiredToken: string,
): Observable<HttpEvent<unknown>> {
  if (!auth.getRefreshToken() && !auth.getUserProfile()) {
    // No legacy refresh token AND no recorded user profile — we have no
    // evidence of a session, so the cookie won't help either. Hard logout.
    // (S-01: refresh token now lives in an HttpOnly cookie that JS cannot read,
    //  so we use the persisted user profile as proof of an active session.)
    auth.logout();
    void router.navigate(['/login']);
    return EMPTY;
  }

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

  // Public auth endpoints that must never carry (possibly stale) credentials.
  // bootstrap-status & bootstrap-signup must work before any user exists,
  // and a leftover expired token would cause the request to silently die.
  const isPublicAuth =
    /\/auth\/login(?:[/?#]|$)/i.test(modified.url) ||
    /\/auth\/bootstrap/i.test(modified.url) ||
    /\/auth\/register/i.test(modified.url) ||
    /\/auth\/password\/request/i.test(modified.url) ||
    /\/auth\/csrf-token/i.test(modified.url) ||
    /\/assignments\/public\//i.test(modified.url);

  if (!isPublicAuth) {
    const token = auth.getToken();

    if (token && auth.isExpired(token)) {
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
