import { HttpClient } from '@angular/common/http';
import { inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { AuthService } from './auth.service';
import { SessionScopeService } from '../core/session-scope.service';
import { refreshWithSupersededRetry } from '../interceptors/error.interceptor';
import { ssoTabState } from '../core/browser-storage';
import { IdleService } from '../core/idle.service';

export { SESSION_RESTORE_HEADER } from './session-lifetime';

/** The account's own credential status: the forced changes the account-setup guard reads. */
interface CredentialStatus {
  forcePasswordChange?: boolean;
  forceUsernameChange?: boolean;
}

/**
 * App initializer: the access token and the profile live in memory only, so a
 * reload starts without them. A tab bound to an account (AuthService.setToken)
 * asks the refresh cookie for a new access token, before the first route is
 * resolved, and then:
 *
 * - keeps it only when it is for the account the tab is bound to — the cookie
 *   is shared by every tab of the browser, so after someone else signed in
 *   elsewhere it names them: the tab is ended locally and signs in again;
 * - reads the account's forced password/username change from the server, so
 *   the account-setup guard still holds after the reload;
 * - runs the session bootstrap for the roles, the profile and the scope.
 *
 * Any of these failing ends the restore locally (no server sign-out): routes
 * open only after a complete restore, otherwise on the sign-in page.
 *
 * The idle lock is the server's: the refresh answer says when the session's
 * screen is locked, and the tab shows its lock screen whatever its own storage
 * remembers. The two calls below are among the few a locked session may make.
 * When the cookie is refused the tab's state is cleared locally and the guards
 * send the user to the sign-in page (with the page they asked for as
 * `returnUrl`).
 *
 * The refresh is marked SESSION_RESTORE_REQUEST: the error interceptor hands
 * its refusal back untouched instead of signing out on the server and
 * redirecting; the shared deadline and the reviewed 409 retry still apply.
 *
 * A tab signed in by SSO has no refresh cookie: OidcAuthService.initialize
 * restores it through the identity provider, so it is left alone here.
 */
export function restoreSessionOnStartup(): Promise<void> {
  const auth = inject(AuthService);
  if (!auth.hasTabSession() || auth.getToken() || ssoTabState()) return Promise.resolve();
  const sessionScope = inject(SessionScopeService);
  const http = inject(HttpClient);
  const idle = inject(IdleService);

  return firstValueFrom(refreshWithSupersededRetry(auth, true))
    .then(async (tokens) => {
      if (!tokens?.accessToken) throw new Error('no access token');
      if (!(await auth.isTokenForThisTab(tokens.accessToken))) {
        throw new Error('another account');
      }
      auth.setToken(tokens.accessToken);
      // The server holds the lock: a tab whose storage no longer says so is locked all the same.
      if (tokens.sessionLocked) idle.lockFromServer();
      // Fail closed: without the credential status or the session bootstrap
      // the forced changes and the scope are unknown, so the tab signs in again
      // rather than opening routes without them.
      const status = await firstValueFrom(http.get<CredentialStatus>('/auth/credentials/me'));
      const session = await firstValueFrom(
        sessionScope.hydrate({
          forcePasswordChange: status?.forcePasswordChange === true,
          forceUsernameChange: status?.forceUsernameChange === true,
        }),
        { defaultValue: null },
      );
      if (!session) throw new Error('session bootstrap unavailable');
    })
    .catch(() => auth.endLocalSession());
}
