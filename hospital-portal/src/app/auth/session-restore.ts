import { inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { AuthService } from './auth.service';
import { SessionScopeService } from '../core/session-scope.service';
import { refreshWithSupersededRetry } from '../interceptors/error.interceptor';

export { SESSION_RESTORE_HEADER } from './session-lifetime';

/**
 * App initializer: the access token and the profile live in memory only, so a
 * reload starts without them. A tab that signed in (and did not sign out)
 * asks the refresh cookie for a new access token, then the session bootstrap
 * for the roles, the profile and the hospital scope, before the first route is
 * resolved. When the cookie is refused the tab's state is cleared locally and
 * the guards send the user to the sign-in page (with the page they asked for
 * as `returnUrl`).
 *
 * The refresh is marked SESSION_RESTORE_REQUEST: the error interceptor hands
 * its refusal back untouched instead of signing out on the server and
 * redirecting; the shared deadline and the reviewed 409 retry still apply.
 */
export function restoreSessionOnStartup(): Promise<void> {
  const auth = inject(AuthService);
  if (!auth.hasTabSession() || auth.getToken()) return Promise.resolve();
  const sessionScope = inject(SessionScopeService);

  return firstValueFrom(refreshWithSupersededRetry(auth, true))
    .then(async (tokens) => {
      if (!tokens?.accessToken) throw new Error('no access token');
      auth.setToken(tokens.accessToken);
      // The bootstrap sets the roles and the profile (or, when it cannot be
      // asked, the token's roles stand in).
      await firstValueFrom(sessionScope.hydrate(), { defaultValue: null });
    })
    .catch(() => auth.endLocalSession());
}
