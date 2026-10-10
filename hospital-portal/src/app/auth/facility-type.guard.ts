import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivateFn, Router, UrlTree } from '@angular/router';
import { Observable, map } from 'rxjs';

import { ProviderContextService } from '../core/provider-context.service';
import { RoleContextService } from '../core/role-context.service';
import { FacilityType } from '../services/provider.model';

/**
 * The provider pages' route guard (provider plan §6.6, beside `RoleGuard`).
 *
 * <p>The backend admits a `/provider/**` call from live assignments at the
 * facility the request acts at, never from a token role, so this guard asks
 * the same question through {@link ProviderContextService}: does this session
 * act at a provider facility of one of `data.facilityTypes`, and, when
 * `data.providerAdmin` is true, as its PROVIDER_ADMIN? Anyone else lands on the
 * not-found page, the portal's counterpart of the backend's identical 404.
 */
export const FacilityTypeGuard: CanActivateFn = (
  route: ActivatedRouteSnapshot,
): Observable<boolean | UrlTree> => {
  const context = inject(ProviderContextService);
  const router = inject(Router);
  const facilityTypes = route.data['facilityTypes'] as FacilityType[] | undefined;
  const adminOnly = route.data['providerAdmin'] === true;

  return context.load().pipe(
    map((settings) => {
      if (!settings) return router.parseUrl('/error/404');
      if (facilityTypes?.length && !facilityTypes.includes(settings.facilityType)) {
        return router.parseUrl('/error/404');
      }
      if (adminOnly && !settings.providerAdmin) return router.parseUrl('/error/404');
      return true;
    }),
  );
};

/**
 * `/dashboard` is the hospital landing page: a provider user is sent to the
 * provider home instead (AC-12), unless they chose to act as a patient.
 */
export const ProviderHomeRedirectGuard: CanActivateFn = (): Observable<boolean | UrlTree> => {
  const context = inject(ProviderContextService);
  const roleContext = inject(RoleContextService);
  const router = inject(Router);

  return context
    .load()
    .pipe(
      map((settings) =>
        settings && roleContext.activeRole !== 'ROLE_PATIENT' ? router.parseUrl('/provider') : true,
      ),
    );
};
