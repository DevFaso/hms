import { effect, inject, untracked } from '@angular/core';

import { ProviderContextService } from '../core/provider-context.service';

/**
 * Keep a provider page on the facility the session acts at. Calls
 * `onFacility` once for the facility the page opens on, and again each time
 * the session moves to another one (a branch switch), never while the
 * context is between answers (`null`), so a re-ask of the same facility does
 * not reload the page. Returns a reader of the facility the page currently
 * shows: tag a request with it, and drop the answer when it no longer matches.
 *
 * Call it in an injection context (a component's field initializer or
 * constructor).
 */
export function followFacility(onFacility: (facilityId: string) => void): () => string | null {
  const context = inject(ProviderContextService);
  let shown: string | null = null;
  effect(() => {
    const facilityId = context.settings()?.facilityId ?? null;
    if (facilityId === null || facilityId === shown) return;
    shown = facilityId;
    untracked(() => onFacility(facilityId));
  });
  return () => shown;
}
