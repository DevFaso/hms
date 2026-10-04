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

/**
 * Calls the portal makes to (re)establish its hospital scope, or to leave.
 * They carry the token but never `X-Hospital-Id`: a selection the user has
 * lost must not travel on the very request that replaces it. Mirrors
 * `ActingScopeResolver.SCOPE_ESTABLISHING_PATHS` on the backend, which
 * ignores a stale header on these paths for callers that still send one.
 */
export const SCOPE_ESTABLISHING_PATTERNS: readonly RegExp[] = [
  /\/auth\/session\/bootstrap(?:[/?#]|$)/i,
  /\/auth\/logout(?:[/?#]|$)/i,
  /\/auth\/token\/refresh(?:[/?#]|$)/i,
  /\/me\/assignments(?:[?#]|$)/i,
];

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
    /\/auth\/verify-email/i.test(modified.url) ||
    /\/auth\/resend-verification/i.test(modified.url) ||
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
    // Cross-tenant: super-admins in "global view" must NOT send
    // X-Hospital-Id, otherwise list endpoints filter to one tenant and
    // the all-hospitals view becomes a single-hospital view in disguise
    // (this is exactly the bug that
    //  docs/super-admin-cross-tenant-design.md was written to fix).
    // For non-super-admins this resolves to their active hospital,
    // preserving the existing single-tenant behaviour.
    const hid = roleCtx.effectiveHospitalIdForRequest();
    const establishesScope = SCOPE_ESTABLISHING_PATTERNS.some((pattern) =>
      pattern.test(modified.url),
    );
    if (hid && !establishesScope) {
      headers['X-Hospital-Id'] = hid;
    }
    if (Object.keys(headers).length) {
      modified = modified.clone({ setHeaders: headers });
    }
  }

  return next(modified);
};
