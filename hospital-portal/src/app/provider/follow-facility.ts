import { effect, inject, untracked } from '@angular/core';

import { ProviderContextService } from '../core/provider-context.service';

/** What a provider page does as the session's facility changes. */
export interface FacilityFollower {
  /** The page opens on a facility, or the session moved to another one: read it. */
  load: (facilityId: string) => void;
  /**
   * The session acts at no provider facility any more (a switch to a
   * hospital scope, a lost seat): drop the shown data, show "not available",
   * offer no action.
   */
  cleared: () => void;
  /** The context could not be read for now: show a retry state, keep nothing actionable. */
  failed: () => void;
}

/**
 * Keep a provider page on the facility the session acts at, following
 * `ProviderContextService.status`:
 * - `provider`: read the page for that facility, once per facility (a re-ask
 *   that answers the same facility does not reload);
 * - `none`: the facility is gone; the page clears itself;
 * - `error`: the context could not be read; the page offers a retry;
 * - `pending`: between answers; nothing changes yet.
 *
 * Returns a reader of the facility the page currently shows (`null` once
 * cleared or failed): tag a request with it and drop the answer when it no
 * longer matches. Call it in an injection context (a component's field
 * initializer or constructor).
 */
export function followFacility(follower: FacilityFollower): () => string | null {
  const context = inject(ProviderContextService);
  let shown: string | null = null;
  let shownKey: string | null = null;
  effect(() => {
    const status = context.status();
    const facilityId = context.settings()?.facilityId ?? null;
    // The admin flag is part of what the page shows (admin-only pages, row
    // actions): a re-ask that keeps the facility but drops the admin role
    // reads the page again, and its 404 then clears it. A key change, never
    // a loop: the same answer twice reloads nothing.
    const key =
      facilityId === null ? null : `${facilityId}:${context.settings()?.providerAdmin === true}`;
    untracked(() => {
      if (status === 'provider' && facilityId !== null && key !== null) {
        if (key === shownKey) return;
        shownKey = key;
        shown = facilityId;
        follower.load(facilityId);
      } else if (status === 'none') {
        shown = null;
        shownKey = null;
        follower.cleared();
      } else if (status === 'error') {
        shown = null;
        shownKey = null;
        follower.failed();
      }
    });
  });
  return () => shown;
}
