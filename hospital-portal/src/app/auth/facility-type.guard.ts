import { Injector, inject } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { ActivatedRouteSnapshot, CanActivateFn, Router, UrlTree } from '@angular/router';
import { Observable, filter, map, of, switchMap, take, timeout } from 'rxjs';

import { ProviderContextService } from '../core/provider-context.service';
import { RoleContextService } from '../core/role-context.service';
import { FacilityType } from '../services/provider.model';

/**
 * How long a guard waits for the session bootstrap. The bootstrap always
 * resolves (its error path marks the session resolved too); this only stops a
 * hung request from holding a navigation forever.
 */
const SESSION_WAIT_MS = 15000;

/**
 * Emits once the session bootstrap has resolved: before that the scope and
 * the provider flag are unknown, and an answer about them is not an answer.
 * A deep link opened on a hard refresh waits here instead of being refused.
 */
function sessionResolved(): Observable<unknown> {
  const roleContext = inject(RoleContextService);
  if (roleContext.sessionResolved()) return of(true);
  return toObservable(roleContext.sessionResolved, { injector: inject(Injector) }).pipe(
    filter((resolved) => resolved),
    take(1),
    timeout({ first: SESSION_WAIT_MS, with: () => of(true) }),
  );
}

/**
 * The provider pages' route guard (provider plan §6.6, beside `RoleGuard`).
 *
 * <p>The backend admits a `/provider/**` call from live assignments at the
 * facility the request acts at, never from a token role, so this guard asks
 * the same question through {@link ProviderContextService}, once the session
 * has resolved: does this session act at a provider facility of one of
 * `data.facilityTypes`, and, when `data.providerAdmin` is true, as its
 * PROVIDER_ADMIN? Anyone else lands on the not-found page, the portal's
 * counterpart of the backend's identical 404.
 */
export const FacilityTypeGuard: CanActivateFn = (
  route: ActivatedRouteSnapshot,
): Observable<boolean | UrlTree> => {
  const context = inject(ProviderContextService);
  const router = inject(Router);
  const facilityTypes = route.data['facilityTypes'] as FacilityType[] | undefined;
  const adminOnly = route.data['providerAdmin'] === true;

  return sessionResolved().pipe(
    switchMap(() => context.load()),
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
 * provider home instead (AC-12), unless they chose to act as a patient. Only a
 * session the bootstrap marked as a provider user asks the server; everyone
 * else, hospital pharmacists and lab staff included, goes straight on.
 */
export const ProviderHomeRedirectGuard: CanActivateFn = (): Observable<boolean | UrlTree> => {
  const context = inject(ProviderContextService);
  const roleContext = inject(RoleContextService);
  const router = inject(Router);

  return sessionResolved().pipe(
    switchMap(() =>
      roleContext.providerUser() && roleContext.activeRole !== 'ROLE_PATIENT'
        ? context.load()
        : of(null),
    ),
    map((settings) => (settings ? router.parseUrl('/provider') : true)),
  );
};
