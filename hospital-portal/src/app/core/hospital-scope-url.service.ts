import { Location } from '@angular/common';
import { GLOBAL_HOSPITAL_HISTORY_STATE } from './hospital-scope-gate.service';
import { Injectable, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';

import { RoleContextService } from './role-context.service';

/**
 * Cross-tenant scope ↔ URL bridge.
 *
 * The super-admin scope chip
 * (docs/super-admin-cross-tenant-design.md) reflects its state in
 * `?hospitalId=…` so back/forward/share-link work and the page can
 * boot directly into a scoped view. There are two consumers:
 *
 *   1. Host pages (consultations, encounters, …) call
 *      `applyUrlScopeSync()` from their `ngOnInit` BEFORE the first
 *      `load()` so the auth interceptor sees the right scope on the
 *      initial fetch. Without this, child-component lifecycle order
 *      would let the host fire its first request under the default
 *      global-view scope and immediately re-fetch after the chip's
 *      `ngOnInit` reads the URL — a flicker users would notice on
 *      `/consultations?hospitalId=...` shared links.
 *
 *   2. The chip itself reuses the read in `ngOnInit` to fetch the
 *      hospital name for its label and to wire up URL writeback on
 *      user-driven scope changes.
 *
 * Both call sites are idempotent: applying the same URL state twice
 * to `RoleContextService` is a no-op.
 */
@Injectable({ providedIn: 'root' })
export class HospitalScopeUrlService {
  private readonly roleContext = inject(RoleContextService);
  private readonly router = inject(Router);
  private readonly location = inject(Location);

  /**
   * Read `?hospitalId=…` from the supplied route's snapshot and mutate
   * `RoleContextService` accordingly. No-op for non-super-admins.
   * Returns the resolved hospital id (or `null` for global view) so
   * callers can short-circuit a hospital-name lookup if absent.
   */
  applyUrlScopeSync(route: ActivatedRoute): string | null {
    if (!this.roleContext.isSuperAdmin()) {
      return null;
    }
    const queryHospitalId = route.snapshot.queryParamMap.get('hospitalId');
    if (queryHospitalId) {
      this.roleContext.scopeToHospital(queryHospitalId);
      return queryHospitalId;
    }
    this.roleContext.enableGlobalView();
    return null;
  }

  /**
   * Write the current scope back into `?hospitalId=` (or remove the
   * param for global view). Uses `replaceUrl: true` so each chip change
   * isn't a separate history entry while the user explores.
   */
  syncScopeToUrl(route: ActivatedRoute, hospitalId: string | null): void {
    // Clearing an inherited scope can leave the URL unchanged, so Router skips
    // NavigationEnd. Preserve the explicit choice before requesting navigation.
    if (this.roleContext.isSuperAdmin()) {
      this.location.replaceState(this.location.path(true), '', {
        ...(this.location.getState() as Record<string, unknown>),
        [GLOBAL_HOSPITAL_HISTORY_STATE]: this.roleContext.globalView(),
      });
    }
    void this.router.navigate([], {
      relativeTo: route,
      queryParams: { hospitalId: hospitalId ?? null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }
}
