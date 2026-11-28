import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';

import { AuthService } from './auth.service';

/**
 * MVP-5: Super-Admin Surface Consolidation.
 *
 * Routes still reachable from bookmarks or older nav links (`/dashboard`,
 * `/admin`) re-route a super admin to the Control Tower so `/super-admin`
 * is the single mission-control. Other roles fall through unchanged.
 */
export const SuperAdminRedirectGuard: CanActivateFn = (): boolean | UrlTree => {
  const auth = inject(AuthService);
  const router = inject(Router);

  return auth.isActiveSuperAdmin() ? router.parseUrl('/super-admin') : true;
};
