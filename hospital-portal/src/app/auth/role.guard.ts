import { inject } from '@angular/core';
import { CanActivateFn, Router, ActivatedRouteSnapshot, UrlTree } from '@angular/router';

import { AuthService } from './auth.service';
import { RoleContextService } from '../core/role-context.service';

export const RoleGuard: CanActivateFn = (route: ActivatedRouteSnapshot): boolean | UrlTree => {
  const auth = inject(AuthService);
  const roleContext = inject(RoleContextService);
  const router = inject(Router);

  const requiredRoles = route.data['roles'] as string[] | undefined;
  if (!requiredRoles?.length) return true;

  const activeRole = roleContext.activeRole;
  if (activeRole) {
    return requiredRoles.includes(activeRole) ? true : router.parseUrl('/error/403');
  }

  if (auth.hasAnyRole(requiredRoles)) {
    return true;
  }

  return router.parseUrl('/error/403');
};
