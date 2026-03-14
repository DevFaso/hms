import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';

import { AuthService } from './auth.service';

/**
 * Redirects users to /account-setup when they must change their
 * username or password before accessing the rest of the application.
 */
export const AccountSetupGuard: CanActivateFn = (): boolean | UrlTree => {
  const auth = inject(AuthService);
  const router = inject(Router);

  const profile = auth.getUserProfile();
  if (profile?.forcePasswordChange || profile?.forceUsernameChange) {
    return router.parseUrl('/account-setup');
  }
  return true;
};
