import { HttpClient } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, firstValueFrom, of } from 'rxjs';

import { AuthService } from './auth.service';
import { SessionScopeService } from '../core/session-scope.service';
import { refreshWithSupersededRetry } from '../interceptors/error.interceptor';

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
 * The idle lock is untouched: a tab locked before the reload stays locked.
 * When the cookie is refused the tab's state is cleared locally and the guards
 * send the user to the sign-in page (with the page they asked for as
 * `returnUrl`).
 *
 * The refresh is marked SESSION_RESTORE_REQUEST: the error interceptor hands
 * its refusal back untouched instead of signing out on the server and
 * redirecting; the shared deadline and the reviewed 409 retry still apply.
 */
export function restoreSessionOnStartup(): Promise<void> {
  const auth = inject(AuthService);
  if (!auth.hasTabSession() || auth.getToken()) return Promise.resolve();
  const sessionScope = inject(SessionScopeService);
  const http = inject(HttpClient);

  return firstValueFrom(refreshWithSupersededRetry(auth, true))
    .then(async (tokens) => {
      if (!tokens?.accessToken) throw new Error('no access token');
      if (!(await auth.isTokenForThisTab(tokens.accessToken))) {
        throw new Error('another account');
      }
      auth.setToken(tokens.accessToken);
      const status = await firstValueFrom(
        http
          .get<CredentialStatus>('/auth/credentials/me')
          .pipe(catchError(() => of<CredentialStatus>({}))),
      );
      // The bootstrap sets the roles and the profile (or, when it cannot be
      // asked, the token's roles stand in), with the forced changes on it.
      await firstValueFrom(
        sessionScope.hydrate({
          forcePasswordChange: status.forcePasswordChange,
          forceUsernameChange: status.forceUsernameChange,
        }),
        { defaultValue: null },
      );
    })
    .catch(() => auth.endLocalSession());
}
