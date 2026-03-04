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
 *
 * NOTE: these are module-level singletons.  Angular's functional interceptors are
 * executed in the same module scope for the lifetime of the app, so this is safe.
 */
let isRefreshing = false;
const refreshDone$ = new BehaviorSubject<string | null>(null);

/**
 * Clone a request and attach a new Bearer token, preserving every other header
 * that was already present (e.g. X-Hospital-Id, Content-Type, Accept, etc.).
 * Using `setHeaders` on `HttpRequest.clone()` merges into the existing headers
 * rather than replacing them, so this is safe.
 */
function cloneWithToken(req: HttpRequest<unknown>, token: string): HttpRequest<unknown> {
  return req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
}

/**
 * Attempt a silent token refresh and then replay the original request.
 *
 * - Only ONE concurrent refresh call is made; all other 401-ing requests queue
 *   on `refreshDone$` and are replayed with the new token once it arrives.
 * - If refresh fails the `__REFRESH_FAILED__` sentinel unblocks all waiters
 *   so they never hang indefinitely.
 * - Returns EMPTY (swallows the error) for background/auxiliary requests so the
 *   user is not ejected from their current view for best-effort API calls.
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
    refreshDone$.next(null); // reset before the call

    return auth.refreshTokenRequest().pipe(
      switchMap((tokens) => {
        isRefreshing = false;
        auth.setToken(tokens.accessToken);
        if (tokens.refreshToken) {
          auth.setRefreshToken(tokens.refreshToken);
        }
        // Unblock all queued requests with the new token.
        refreshDone$.next(tokens.accessToken);

        // Replay the original request with the fresh token.
        // req here is the fully-prefixed request from the error interceptor,
        // so its URL is already correct — we only swap the Authorization header.
        return next(cloneWithToken(req, tokens.accessToken));
      }),
      catchError((refreshError) => {
        isRefreshing = false;
        // Emit sentinel so any queued requests unblock immediately instead of
        // hanging forever waiting for a non-null value.
        refreshDone$.next('__REFRESH_FAILED__');
        // Reset to null so refreshDone$ is ready for the next login session.
        refreshDone$.next(null);
        // Refresh token is expired/invalid → full logout.
        auth.logout();
        void router.navigate(['/login']);
        return isSilentBackground ? EMPTY : throwError(() => refreshError);
      }),
    );
  }

  // Another request is already refreshing — queue behind it.
  // Filter out null (initial value) and the failure sentinel so we only proceed
  // when a real new access token has been issued.
  return refreshDone$.pipe(
    filter((t): t is string => t !== null && t !== '__REFRESH_FAILED__'),
    take(1),
    switchMap((newToken) => next(cloneWithToken(req, newToken))),
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
