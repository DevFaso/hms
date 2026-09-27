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
import { SessionScopeService } from '../core/session-scope.service';
import { ImpersonationService } from '../services/impersonation.service';
import { DowntimeService } from '../services/downtime.service';

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

/**
 * POSTs that are reads in disguise and feed a background widget. A CDS Hooks
 * invocation (`POST /cds-services/{id}`) is the BPA panel's evaluate call on
 * chart load: the panel is gated to the clinician roles the backend admits,
 * but if the two ever drift the chart must still open — the panel degrades to
 * its error text instead of the whole page redirecting to /error/403.
 */
const SILENT_403_POST_PATTERNS = [/\/cds-services\/[^/?]+(\?|$)/];

const SILENT_401_PATTERNS = [/\/chat\/mark-read\//];

/**
 * URL+method pairs already reported this session — a background GET that keeps
 * polling a forbidden endpoint must not flood the audit log.
 */
const reportedSilent403s = new Set<string>();

/**
 * Reset the once-per-URL dedup on logout so the next session's authorization
 * drift is reported again (module state outlives the Angular injector).
 */
export function clearReportedSilent403s(): void {
  reportedSilent403s.clear();
}

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

/**
 * The 403 the backend gives a hospital scope it refuses (the one tenant
 * resolver): `code` is `hospital_scope_refused` and `reason` says why.
 * `NO_LONGER_PERMITTED` means the scope this session holds names a hospital
 * the user was revoked at — a stale chip — so the portal re-reads its scope
 * from the server instead of showing the forbidden page. Every reason is a
 * scope problem the page itself reports, never a page-level 403.
 */
const HOSPITAL_SCOPE_REFUSED = 'hospital_scope_refused';

function hospitalScopeRefusal(error: HttpErrorResponse): string | null {
  const body = error.error as { code?: unknown; reason?: unknown } | null;
  if (body && typeof body === 'object' && body.code === HOSPITAL_SCOPE_REFUSED) {
    return typeof body.reason === 'string' ? body.reason : '';
  }
  return null;
}

/** One re-bootstrap at a time: every request in flight carries the same stale chip. */
let rebootstrappingScope = false;

function rebootstrapScope(sessionScope: SessionScopeService): void {
  if (rebootstrappingScope) return;
  rebootstrappingScope = true;
  sessionScope.hydrate().subscribe({
    complete: () => (rebootstrappingScope = false),
    error: () => (rebootstrappingScope = false),
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
  const impersonation = inject(ImpersonationService);
  // Hoisted: inject() is only valid during the synchronous interceptor
  // call, not inside the async catchError callback below.
  const downtime = inject(DowntimeService);
  const sessionScope = inject(SessionScopeService);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401) {
        const isRefreshCall = req.url.includes('/auth/token/refresh');
        const isVerifyPassword = req.url.includes('/auth/verify-password');

        // Closes Copilot review #4 on PR #224. If the 401 fires while an
        // impersonation token is in use (typically because the 30-min TTL
        // just elapsed), DO NOT auto-refresh — the surviving refresh
        // cookie would otherwise mint a fresh super-admin access token,
        // silently elevating the request without an IMPERSONATION_ENDED
        // audit boundary. Instead, drop the impersonation token, clear
        // the banner, and bounce the operator back to /super-admin where
        // they can re-start the session if they still need it. The
        // backend ImpersonationSessionTracker enforces the same rule
        // server-side; this client-side check is defense in depth and a
        // cleaner UX (no spurious refresh round-trip).
        if (impersonation.isActive() && !isRefreshCall) {
          impersonation.forceStop();
          void router.navigate(['/super-admin']);
          return throwError(() => error);
        }

        if (
          !isRefreshCall &&
          !isVerifyPassword &&
          (auth.getRefreshToken() || auth.getUserProfile())
        ) {
          // We either have a legacy refresh token in storage OR a recorded
          // session profile (S-01 cookie-based refresh). Attempt silent renewal.
          return tryRefreshAndRetry(req, next, auth, router);
        }

        const isSilentBackground = SILENT_401_PATTERNS.some((p) => p.test(req.url));
        if (!isVerifyPassword && !isSilentBackground) {
          auth.logout();
          void router.navigate(['/login']);
        }
      } else if (error.status === 503 && error.headers?.get('X-Readonly-Mode') === 'true') {
        // Downtime read-only mode (P3 #23a): flip the banner on immediately
        // instead of waiting for the next poll; the component-level error
        // handler still gets the refusal (rethrown below) so the user sees
        // why their save failed.
        const body = error.error as { message?: string } | null;
        downtime.markReadOnly(body?.message ?? null);
      } else if (error.status === 403 && hospitalScopeRefusal(error) !== null) {
        // A refused hospital scope is the page's to report, not a forbidden
        // page; a stale chip is corrected by re-reading the scope.
        if (hospitalScopeRefusal(error) === 'NO_LONGER_PERMITTED') {
          rebootstrapScope(sessionScope);
        }
      } else if (error.status === 403) {
        // Never redirect (or re-report) when the audit sink itself is forbidden.
        const isAuditCall = req.url.includes('/frontend-audit');
        const isSilent =
          ((req.method === 'GET' || req.method === 'HEAD') &&
            SILENT_403_PATTERNS.some((pattern) => pattern.test(req.url))) ||
          (req.method === 'POST' &&
            SILENT_403_POST_PATTERNS.some((pattern) => pattern.test(req.url)));
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
