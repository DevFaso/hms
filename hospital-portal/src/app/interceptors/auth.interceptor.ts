import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';

import { environment } from '../../environments/environment';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';

export const apiPrefixInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const roleCtx = inject(RoleContextService);

  // Skip absolute URLs and i18n assets
  if (
    /^https?:\/\//i.test(req.url) ||
    /^assets\/i18n\//i.test(req.url) ||
    req.url.includes('assets/i18n/')
  ) {
    return next(req);
  }

  // Normalize path
  let path = req.url;

  // Prevent double /api/api prefix
  if (/^\/?api\//i.test(path)) {
    path = path.replace(/^\/?api\//i, '/');
  }

  // Build final URL
  const finalUrl = `${environment.apiBase}${path.startsWith('/') ? path : '/' + path}`;

  let modified = req.clone({ url: finalUrl });

  // Add Authorization header for API requests (skip auth endpoints)
  if (!/\/auth\/login(?:[/?#]|$)/i.test(modified.url)) {
    const headers: Record<string, string> = {};
    const token = auth.getToken();
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
