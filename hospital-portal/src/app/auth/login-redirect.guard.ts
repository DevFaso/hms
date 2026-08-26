import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivateFn, Router } from '@angular/router';

import { AuthService } from './auth.service';
import { safeReturnUrl } from './return-url';

/**
 * Keeps an already-authenticated user off the login page.
 *
 * <p>Honours `returnUrl` for the same reason the login component does: a
 * patient who is still signed in and clicks an appointment link from an
 * email hits `/login?returnUrl=...` on the way through, and sending them to
 * their landing page instead of the appointment would look like the link
 * was ignored.
 */
export const LoginRedirectGuard: CanActivateFn = (route: ActivatedRouteSnapshot) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.isAuthenticated()) {
    const returnUrl = safeReturnUrl(route.queryParamMap.get('returnUrl'));
    return router.parseUrl(returnUrl ?? auth.resolveLandingPath());
  }
  return true;
};
