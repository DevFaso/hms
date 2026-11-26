import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';

/**
 * The `reason` query parameter of the sign-in page when a session ended a
 * fixed time after its sign-in (the server's absolute session lifetime).
 */
export const SESSION_LIFETIME_REASON = 'session-ended';

/**
 * The refresh's 401 says the session reached its absolute lifetime: the
 * server sends `WWW-Authenticate: Bearer … error_description="session_lifetime"`.
 * Any other refusal (revoked, reused, idle) is not this one.
 */
export function isSessionLifetimeEnd(error: unknown): boolean {
  if (!(error instanceof HttpErrorResponse) || error.status !== 401) return false;
  const challenge = error.headers?.get('WWW-Authenticate') ?? '';
  return /error_description="session_lifetime"/.test(challenge);
}

/**
 * To the sign-in page after a refused refresh; when the session reached its
 * lifetime, the page says so (`?reason=session-ended`).
 */
export function navigateToSignIn(router: Router, error: unknown): Promise<boolean> {
  return isSessionLifetimeEnd(error)
    ? router.navigate(['/login'], { queryParams: { reason: SESSION_LIFETIME_REASON } })
    : router.navigate(['/login']);
}

/** The refresh minted a token for another account than the tab's (see AuthService.isTokenForShownAccount). */
export const ACCOUNT_CHANGED_CODE = 'SESSION_ACCOUNT_CHANGED';

export function accountChangedError(): HttpErrorResponse {
  return new HttpErrorResponse({
    status: 401,
    statusText: 'Unauthorized',
    error: { code: ACCOUNT_CHANGED_CODE },
  });
}

/**
 * The tab's session ended because another account signed in from this
 * browser: it is ended locally only — the server session is the other tab's.
 */
export function isAccountChanged(error: unknown): boolean {
  return (
    error instanceof HttpErrorResponse &&
    (error.error as { code?: unknown } | null)?.code === ACCOUNT_CHANGED_CODE
  );
}
