import {
  HttpInterceptorFn,
  HttpErrorResponse,
  HttpHandlerFn,
  HttpRequest,
  HttpEvent,
} from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import {
  BehaviorSubject,
  EMPTY,
  Observable,
  catchError,
  filter,
  switchMap,
  take,
  throwError,
} from 'rxjs';
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
 * Fire-and-forget requests that should NOT trigger a logout or redirect on 401
 * even if the silent refresh below also fails.  These are best-effort background
 * calls where failing silently is preferable to ejecting the user.
 */
const SILENT_401_PATTERNS = [
  /\/chat\/mark-read\//, // PUT /chat/mark-read/{sender}/{recipient} — best-effort read receipt
];

/**
 * Token refresh state — shared across concurrent requests so only ONE refresh
 * call goes to the server at a time (all others queue and retry with the new token).
 */
let isRefreshing = false;
const refreshDone$ = new BehaviorSubject<string | null>(null);

/**
 * Attempt a silent token refresh and then replay the original request.
 * Returns EMPTY (swallows the error) if the refresh also fails AND the URL
 * matches a SILENT_401_PATTERNS entry.
 */
function tryRefreshAndRetry(
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
  auth: AuthService,
  router: Router,
): Observable<HttpEvent<unknown>> {
  const isSilentBackground = SILENT_401_PATTERNS.some((p) => p.test(req.url));

  if (!isRefreshing) {
    isRefreshing = true;
    refreshDone$.next(null); // reset

    return auth.refreshTokenRequest().pipe(
      switchMap((tokens) => {
        isRefreshing = false;
        auth.setToken(tokens.accessToken);
        if (tokens.refreshToken) {
          auth.setRefreshToken(tokens.refreshToken);
        }
        refreshDone$.next(tokens.accessToken);

        // Replay the original request with the new token
        const retried = req.clone({
          setHeaders: { Authorization: `Bearer ${tokens.accessToken}` },
        });
        return next(retried);
      }),
      catchError((refreshError) => {
        isRefreshing = false;
        // Emit a sentinel so any queued requests unblock and get redirected
        // instead of hanging indefinitely waiting for a non-null token.
        refreshDone$.next('__REFRESH_FAILED__');
        refreshDone$.next(null);
        // Refresh token is also expired / invalid → full logout
        auth.logout();
        void router.navigate(['/login']);
        return isSilentBackground ? EMPTY : throwError(() => refreshError);
      }),
    );
  }

  // Another request is already refreshing — wait for the new token then retry.
  // Filter out the failure sentinel so we only proceed with a real token.
  return refreshDone$.pipe(
    filter((t): t is string => t !== null && t !== '__REFRESH_FAILED__'),
    take(1),
    switchMap((newToken) => {
      const retried = req.clone({ setHeaders: { Authorization: `Bearer ${newToken}` } });
      return next(retried);
    }),
  );
}

export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const auth = inject(AuthService);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401) {
        // Never try to refresh the refresh-token request itself — that would
        // cause infinite recursion and means the session is truly over.
        const isRefreshCall = req.url.includes('/auth/token/refresh');
        const isVerifyPassword = req.url.includes('/auth/verify-password');

        if (!isRefreshCall && !isVerifyPassword && auth.getRefreshToken()) {
          // We have a refresh token — attempt silent renewal and replay.
          return tryRefreshAndRetry(req, next, auth, router);
        }

        // No refresh token (or this IS the refresh call / verify-password).
        // Fall back to the previous behaviour: silent-fail for background calls,
        // logout+redirect for everything else.
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
