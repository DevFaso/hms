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
  defer,
  filter,
  retry,
  switchMap,
  take,
  throwError,
  timer,
} from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { SessionScopeService } from '../core/session-scope.service';
import { ImpersonationService } from '../services/impersonation.service';
import { DowntimeService } from '../services/downtime.service';
import { STEP_UP_REQUEST, isStepUpRefusal } from '../services/step-up';
import { BACKGROUND_REQUEST } from '../services/ws-transport';

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
  // The bed board's held-bed overlay (QA D188): a role the server keeps off
  // the transfer worklist (decision D5) still keeps the board it is reading.
  /\/transfers\/pending(\?|$)/,
];

/**
 * POSTs that are reads in disguise and feed a background widget. A CDS Hooks
 * invocation (`POST /cds-services/{id}`) is the BPA panel's evaluate call on
 * chart load: the panel is gated to the clinician roles the backend admits,
 * but if the two ever drift the chart must still open — the panel degrades to
 * its error text instead of the whole page redirecting to /error/403.
 */
const SILENT_403_POST_PATTERNS = [
  /\/cds-services\/[^/?]+(\?|$)/,
  // The WebSocket ticket exchange (QA D35): the sockets retry with backoff;
  // a refusal must never take the page the user is on to /error/403.
  /\/auth\/ws-ticket(\?|$)/,
];

/**
 * Clinical actions whose refusal the page itself reports in a toast (D54).
 * A 403 on "sign this prescription" is a business answer — not yours to
 * sign, co-sign outstanding — and redirecting to /error/403 threw the
 * prescriber off the list with no word on why.
 */
const PAGE_HANDLED_403_ACTION_PATTERNS = [
  /\/prescriptions\/[^/?]+\/(sign|cosign|pharmacist-verify)(\?|$)/,
];

const SILENT_401_PATTERNS = [/\/chat\/mark-read\//];

/** Requests whose 401 means "the password you typed is wrong". */
export const CREDENTIAL_CHECK_PATTERNS: readonly RegExp[] = [
  /\/auth\/verify-password(?:[/?#]|$)/i,
  /\/auth\/me\/change-password(?:[/?#]|$)/i,
  /\/auth\/me\/change-email(?:[/?#]|$)/i,
];

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

/**
 * The hospital a NO_LONGER_PERMITTED refusal names: the one refused, which is
 * not necessarily the selection (a stale link to B while A is selected).
 */
function refusedHospitalId(error: HttpErrorResponse): string | null {
  const body = error.error as { hospitalId?: unknown } | null;
  return body && typeof body === 'object' && typeof body.hospitalId === 'string'
    ? body.hospitalId
    : null;
}

/**
 * A refused MFA step-up (X-Mfa-Token): 403 with a code under `mfa.stepup.`.
 * The session is fine, so this is neither a sign-out nor a forbidden page:
 * the page that asked for the code shows why it was refused.
 */
export function mfaStepUpRefusal(error: HttpErrorResponse): string | null {
  const body = error.error as { code?: unknown } | null;
  return body &&
    typeof body === 'object' &&
    typeof body.code === 'string' &&
    body.code.startsWith('mfa.stepup.')
    ? body.code
    : null;
}

function hospitalScopeRefusal(error: HttpErrorResponse): string | null {
  const body = error.error as { code?: unknown; reason?: unknown } | null;
  if (body && typeof body === 'object' && body.code === HOSPITAL_SCOPE_REFUSED) {
    return typeof body.reason === 'string' ? body.reason : '';
  }
  return null;
}

/** One re-bootstrap at a time: every request in flight carries the same stale chip. */
let rebootstrappingScope = false;

/**
 * Forget exactly the hospital the server refused. Only when it was the
 * selection does the session re-read its scope: a stale link to B must not
 * wipe a valid A, and must not start a bootstrap loop.
 */
function forgetRefusedHospital(
  sessionScope: SessionScopeService,
  roleContext: RoleContextService,
  refused: string | null,
): void {
  if (!refused) return;
  const wasSelected = roleContext.effectiveHospitalIdForRequest() === refused;
  // Forget it first: if the bootstrap cannot be reached the stored profile
  // stands in, and it must not bring the refused hospital back.
  sessionScope.forgetHospital(refused);
  if (wasSelected) {
    rebootstrapScope(sessionScope);
  }
}

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

/**
 * A 409 from the refresh means another tab of this browser rotated the shared
 * refresh cookie a moment earlier ("superseded"). The browser now holds the
 * rotated cookie, so asking again shortly after succeeds: retry instead of
 * signing out, which would also revoke the other tab's fresh session.
 */
export const REFRESH_SUPERSEDED_RETRIES = 4;
/** Backs off 600, 1200, 1800, 2400 ms: about 6 s for the winning tab's cookie to land on a slow link. */
export const REFRESH_SUPERSEDED_DELAY_MS = 600;

export function refreshWithSupersededRetry(
  auth: AuthService,
): Observable<{ accessToken: string; refreshToken: string }> {
  // defer: each retry builds the request again, so a body-borne (legacy)
  // refresh token is re-read after the other tab stored the rotated one.
  return defer(() => auth.refreshTokenRequest()).pipe(
    retry({
      count: REFRESH_SUPERSEDED_RETRIES,
      delay: (err: unknown, attempt: number) =>
        err instanceof HttpErrorResponse && err.status === 409
          ? timer(REFRESH_SUPERSEDED_DELAY_MS * attempt)
          : throwError(() => err),
    }),
  );
}

function cloneWithToken(req: HttpRequest<unknown>, token: string): HttpRequest<unknown> {
  return req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
}

/**
 * Whether the bearer the request carried had already expired (QA D342). With
 * no token in storage the session lives in the refresh cookie, so the 401 can
 * only be the session's: treated as expired.
 */
function sentAnExpiredBearer(auth: AuthService): boolean {
  const token = auth.getToken();
  return !token || auth.isExpired(token);
}

/** Wraps the retried credential-check error so it bypasses the refresh-failure sign-out. */
class CredentialRetryError {
  constructor(readonly error: unknown) {}
}

function tryRefreshAndRetry(
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
  auth: AuthService,
  router: Router,
  credentialCheck = false,
): Observable<HttpEvent<unknown>> {
  const isSilentBackground = SILENT_401_PATTERNS.some((p) => p.test(req.url));

  if (!isRefreshing) {
    isRefreshing = true;
    refreshDone$.next(null); // reset before the call

    return refreshWithSupersededRetry(auth).pipe(
      switchMap((tokens) => {
        isRefreshing = false;
        auth.setToken(tokens.accessToken);
        if (tokens.refreshToken) {
          auth.setRefreshToken(tokens.refreshToken);
        }
        // Unblock all queued requests with the new token.
        refreshDone$.next(tokens.accessToken);

        const retried = next(cloneWithToken(req, tokens.accessToken));
        // A credential check retried on a fresh token that still answers 401
        // means the typed password is wrong: hand it to the form, never sign
        // out (D87). The marker lets the catchError below tell it apart from
        // a refresh failure.
        return credentialCheck
          ? retried.pipe(catchError((e) => throwError(() => new CredentialRetryError(e))))
          : retried;
      }),
      catchError((refreshError) => {
        if (refreshError instanceof CredentialRetryError) {
          return throwError(() => refreshError.error);
        }
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
  const roleContext = inject(RoleContextService);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      // A refused MFA step-up is the dialog's to report: never a refresh, a
      // sign-out or the forbidden page (an MFA-enrolled super-admin used to be
      // signed out by suspending a facility without a code).
      if (req.context.get(STEP_UP_REQUEST) && isStepUpRefusal(error)) {
        return throwError(() => error);
      }
      if (error.status === 401) {
        // AuthService.logout() fires POST /auth/logout while it signs out.
        // Its 401 (an already-expired bearer) must not start a refresh, which
        // would mint a new session mid-logout, nor call logout() again.
        if (req.url.includes('/auth/logout')) {
          return throwError(() => error);
        }
        const isRefreshCall = req.url.includes('/auth/token/refresh');
        // Endpoints that check the password typed in the form answer a wrong
        // one with 401 (the mobile apps rely on it). That is a form error, not
        // an expired session: refreshing and then signing out threw the user
        // out of their own profile (D87).
        const isVerifyPassword = CREDENTIAL_CHECK_PATTERNS.some((p) => p.test(req.url));

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

        // QA D342: a credential check sent with a live access token was
        // refused for the password typed, not for the session. Refreshing and
        // retrying sent the wrong password a second time (and spent a second
        // attempt against the lockout); only an expired bearer is retried.
        if (isVerifyPassword && !sentAnExpiredBearer(auth)) {
          return throwError(() => error);
        }

        if (!isRefreshCall && (auth.getRefreshToken() || auth.getUserProfile())) {
          // We either have a legacy refresh token in storage OR a recorded
          // session profile (S-01 cookie-based refresh). Attempt silent renewal.
          // A credential check is refreshed and retried once too (the access
          // token may have expired while the form was open); a 401 on the
          // retry is the wrong-password answer and reaches the form (D87).
          return tryRefreshAndRetry(req, next, auth, router, isVerifyPassword);
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
      } else if (error.status === 403 && mfaStepUpRefusal(error) !== null) {
        // A refused MFA step-up: rethrown to the page, no redirect.
      } else if (error.status === 403 && hospitalScopeRefusal(error) !== null) {
        // A refused hospital scope is the page's to report, not a forbidden
        // page; a stale chip is corrected by re-reading the scope.
        if (hospitalScopeRefusal(error) === 'NO_LONGER_PERMITTED') {
          forgetRefusedHospital(sessionScope, roleContext, refusedHospitalId(error));
        }
      } else if (error.status === 403) {
        // Never redirect (or re-report) when the audit sink itself is forbidden.
        const isAuditCall = req.url.includes('/frontend-audit');
        const isSilent =
          req.context.get(BACKGROUND_REQUEST) ||
          ((req.method === 'GET' || req.method === 'HEAD') &&
            SILENT_403_PATTERNS.some((pattern) => pattern.test(req.url))) ||
          (req.method === 'POST' &&
            SILENT_403_POST_PATTERNS.some((pattern) => pattern.test(req.url)));
        const isPageHandledAction =
          req.method !== 'GET' &&
          PAGE_HANDLED_403_ACTION_PATTERNS.some((pattern) => pattern.test(req.url));
        if (isPageHandledAction) {
          // The page's error handler shows the refusal; no redirect.
        } else if (isSilent) {
          reportSilent403(http, req);
        } else if (!isAuditCall) {
          // A server refusal: the portal cannot know which role it wanted.
          roleContext.noteRefusal(null);
          void router.navigate(['/error/403']);
        }
      }
      return throwError(() => error);
    }),
  );
};
