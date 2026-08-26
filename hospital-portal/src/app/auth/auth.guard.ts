import { inject } from '@angular/core';
import { CanActivateFn, Router, RouterStateSnapshot, UrlTree } from '@angular/router';

import { AuthService } from './auth.service';

/**
 * Gate on authentication, preserving where the user was trying to go.
 *
 * <p>The `returnUrl` matters more than it looks. Every deep link this app
 * emits arrives by email — appointment cancel/reschedule, staff onboarding,
 * assignment confirmation — so the recipient is, by definition, usually not
 * signed in yet when they click. Redirecting to a bare `/login` dropped the
 * target and dumped them on their default landing page, which reads as "the
 * link did nothing".
 */
export const AuthGuard: CanActivateFn = (_route, state: RouterStateSnapshot): boolean | UrlTree => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isAuthenticated()) {
    return true;
  }
  return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};
