import { Injectable, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRouteSnapshot, NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';

import { RoleContextService } from './role-context.service';

/** Route `data` key: `{ requiresHospitalScope: true }` marks a page whose data belongs to one facility. */
export const REQUIRES_HOSPITAL_SCOPE = 'requiresHospitalScope';

/** True when the activated route, or any route above it, carries the flag. */
export function routeRequiresHospitalScope(root: ActivatedRouteSnapshot): boolean {
  for (let node: ActivatedRouteSnapshot | null = root; node; node = node.firstChild) {
    if (node.data[REQUIRES_HOSPITAL_SCOPE] === true) {
      return true;
    }
  }
  return false;
}

/**
 * The shell-level hospital scope gate (Standing platform debt, from the #566
 * self-review). A route flagged `requiresHospitalScope` renders only once a
 * hospital is pinned: staff always are, by their assignment; a super-admin in
 * global view sees the scope chip and the hint in place of the page. A gated
 * page therefore never fires a one-facility request into global view, and the
 * `X-Hospital-Id` the interceptor sends is the scope the page believes it has.
 *
 * On every navigation the gate re-reads the flag from the deepest activated
 * route and honours a `?hospitalId=` in the URL (shared links); without one
 * the current pick is kept, so a super-admin moving between gated pages picks
 * once. `outletKey` follows the effective hospital on a gated route and the
 * shell keys the router outlet on it, so a scope change rebuilds the page
 * instead of trusting every page to reload itself.
 */
@Injectable({ providedIn: 'root' })
export class HospitalScopeGateService {
  private readonly router = inject(Router);
  private readonly roleContext = inject(RoleContextService);

  private readonly _required = signal(false);

  /** The current route is one facility's page. */
  readonly required = this._required.asReadonly();

  /** A gated route with no hospital in scope: the shell shows the chip and hint instead of the outlet. */
  readonly blocked = computed(() => this._required() && !this.roleContext.hasHospitalScope());

  /** The chip belongs above a gated page only for the one account that can switch: a super-admin. */
  readonly showChip = computed(() => this._required() && this.roleContext.isSuperAdmin());

  /** Identity of the outlet: the effective hospital on a gated route, constant elsewhere. */
  readonly outletKey = computed(() =>
    this._required() ? (this.roleContext.effectiveHospitalIdForRequest() ?? 'unscoped') : 'static',
  );

  constructor() {
    this.router.events
      .pipe(
        filter((e): e is NavigationEnd => e instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.onNavigated());
  }

  private onNavigated(): void {
    const root = this.router.routerState.snapshot.root;
    const required = routeRequiresHospitalScope(root);
    if (required && this.roleContext.isSuperAdmin()) {
      const fromUrl = root.queryParamMap.get('hospitalId');
      if (fromUrl) {
        this.roleContext.scopeToHospital(fromUrl);
      }
    }
    this._required.set(required);
  }
}
