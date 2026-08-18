import {
  HttpClient,
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

const SILENT_403_PATTERNS = [
  /\/hospitals(\?|$|\/$)/,
  /\/hospitals\/[^/]+(\?|$)/,
  /\/departments(\?|$|\/)/,
  /\/organizations(\?|$|\/)/,
  /\/staff(\?|$|\/)/,
  /\/roles(\?|$|\/)/,
  /\/notifications(\?|$|\/)/,
  /\/lab-test-definitions(\?|$|\/)/,
  /\/lab-orders(\?|$|\/)/,
  /\/lab-results(\?|$|\/)/,
  /\/lab-specimens(\?|$|\/)/,
];

const SILENT_401_PATTERNS = [/\/chat\/mark-read\//];

/**
 * URL+method pairs already reported this session — a background GET that keeps
 * polling a forbidden endpoint must not flood the audit log.
 */
const reportedSilent403s = new Set<string>();

/**
 * A 403 on a SILENT_403_PATTERNS URL skips the /error/403 redirect so
 * background widgets fail quietly — but the failure must not be invisible.
 * Report it once per URL to the backend audit sink so authorization drift
 * shows up in telemetry. (The error itself still propagates to the caller.)
 */
function reportSilent403(http: HttpClient, req: HttpRequest<unknown>): void {
  const key = `${req.method} ${req.urlWithParams}`;
  if (reportedSilent403s.has(key)) return;
  reportedSilent403s.add(key);
  http
    .post('/frontend-audit', {
      type: 'SILENT_403',
      meta: { url: req.urlWithParams, method: req.method },
      ts: new Date().toISOString(),
    })
    .subscribe({
      error: () => {
        // Telemetry is best-effort — never surface its own failures.
      },
    });
}

let isRefreshing = false;
const refreshDone$ = new BehaviorSubject<string | null>(null);

function cloneWithToken(req: HttpRequest<unknown>, token: string): HttpRequest<unknown> {
  return req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
}

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

        return next(cloneWithToken(req, tokens.accessToken));
      }),
      catchError((refreshError) => {
        isRefreshing = false;
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

  return refreshDone$.pipe(
    filter((t): t is string => t !== null),
    take(1),
    switchMap((newToken) => {
      if (newToken === '__REFRESH_FAILED__') {
        return throwError(
          () => new HttpErrorResponse({ status: 401, statusText: 'Refresh failed' }),
        );
      }
      return next(cloneWithToken(req, newToken));
    }),
  );
}

export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const auth = inject(AuthService);
  const http = inject(HttpClient);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401) {
        const isRefreshCall = req.url.includes('/auth/token/refresh');
        const isVerifyPassword = req.url.includes('/auth/verify-password');

        if (!isRefreshCall && !isVerifyPassword && auth.getRefreshToken()) {
          // We have a refresh token — attempt silent renewal and replay.
          return tryRefreshAndRetry(req, next, auth, router);
        }

        const isSilentBackground = SILENT_401_PATTERNS.some((p) => p.test(req.url));
        if (!isVerifyPassword && !isSilentBackground) {
          auth.logout();
          void router.navigate(['/login']);
        }
      } else if (error.status === 403) {
        // Never redirect (or re-report) when the audit sink itself is forbidden.
        const isAuditCall = req.url.includes('/frontend-audit');
        const isSilent =
          (req.method === 'GET' || req.method === 'HEAD') &&
          SILENT_403_PATTERNS.some((pattern) => pattern.test(req.url));
        if (isSilent) {
          reportSilent403(http, req);
        } else if (!isAuditCall) {
          void router.navigate(['/error/403']);
        }
      }
      return throwError(() => error);
    }),
  );
};
