import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

import { AuthService } from '../auth/auth.service';

/**
 * Background / auxiliary API calls that should fail silently on 403.
 * These are dropdown-population requests — a 403 just means the user
 * does not have access to that resource; we should NOT redirect them
 * away from the current page.
 */
const SILENT_403_PATTERNS = [
  /\/hospitals(\?|$|\/$)/, // GET /hospitals (list for dropdown)
  /\/hospitals\/[^/]+(\?|$)/, // GET /hospitals/:id
  /\/departments(\?|$|\/)/, // GET /departments
  /\/organizations(\?|$|\/)/, // GET /organizations
  /\/staff(\?|$|\/)/, // GET /staff (dropdown)
  /\/roles(\?|$|\/)/, // GET /roles (dropdown)
  /\/notifications(\?|$|\/)/, // GET /notifications (notification panel)
];

/**
 * Fire-and-forget requests that should NOT trigger a logout or redirect on 401.
 * These are best-effort background calls; a 401 here means the token expired
 * between the triggering request (which succeeded) and this side-effect call.
 * The user should remain on their current page; the primary action succeeded.
 */
const SILENT_401_PATTERNS = [
  /\/chat\/mark-read\//, // PUT /chat/mark-read/{sender}/{recipient} — best-effort read receipt
];

export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const auth = inject(AuthService);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401) {
        // Don't auto-logout on failed lock-screen password verification.
        // Also skip logout for best-effort background calls (e.g. mark-read)
        // where the token may have expired between the triggering request and
        // this side-effect call — the user should stay on their current page.
        const isVerifyPassword = req.url.includes('/auth/verify-password');
        const isSilentBackground = SILENT_401_PATTERNS.some((p) => p.test(req.url));
        if (!isVerifyPassword && !isSilentBackground) {
          auth.logout();
          void router.navigate(['/login']);
        }
      } else if (error.status === 403) {
        // Only redirect to the error page for page-level 403s.
        // Auxiliary / dropdown requests (hospitals list, etc.) are silenced
        // so they don't eject the user from their current view.
        const isSilent =
          (req.method === 'GET' || req.method === 'HEAD') &&
          SILENT_403_PATTERNS.some((pattern) => pattern.test(req.url));
        if (!isSilent) {
          void router.navigate(['/error/403']);
        }
      }
      return throwError(() => error);
    }),
  );
};
