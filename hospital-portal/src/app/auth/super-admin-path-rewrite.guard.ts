import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';

import { AuthService } from './auth.service';
import { RoleContextService } from '../core/role-context.service';

/**
 * MVP-5b: when the active role is SUPER_ADMIN, rewrite a legacy
 * top-level URL (e.g. `/feature-flags`) to its `/super-admin/*`
 * counterpart so the super-admin surface is consistently namespaced.
 *
 * <p>Distinct from `SuperAdminRedirectGuard`, which redirects to the
 * Control Tower regardless of the original path. This guard preserves
 * the path semantics — only the prefix changes — so a bookmark to
 * `/feature-flags` lands on `/super-admin/feature-flags`, not
 * `/super-admin`.
 *
 * <p>Other roles fall through unchanged so HOSPITAL_ADMIN / ADMIN
 * navigating to `/audit-logs` or `/platform` keep their existing flow.
 *
 * @param targetPath the `/super-admin/*` path to rewrite to. Must
 *     include the leading slash.
 */
export function superAdminPathRewriteGuard(targetPath: string): CanActivateFn {
  return (): boolean | UrlTree => {
    const auth = inject(AuthService);
    const roleContext = inject(RoleContextService);
    const router = inject(Router);

    const activeRole = roleContext.activeRole;
    const isSuperAdmin = activeRole
      ? activeRole === 'ROLE_SUPER_ADMIN'
      : auth.hasAnyRole(['ROLE_SUPER_ADMIN']);

    return isSuperAdmin ? router.parseUrl(targetPath) : true;
  };
}
