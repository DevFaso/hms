import { Injectable } from '@angular/core';

const STORAGE_KEY = 'hms.nav.order';

@Injectable({ providedIn: 'root' })
export class NavOrderService {
  /**
   * Persist a new route-order array to localStorage.
   * The array contains route strings in the user's desired order.
   */
  save(routes: string[]): void {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(routes));
    } catch {
      // Storage quota exceeded or private-browsing restriction — ignore silently.
    }
  }

  /**
   * Load the persisted order.  Returns null when nothing has been saved yet.
   */
  load(): string[] | null {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return null;
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? (parsed as string[]) : null;
    } catch {
      return null;
    }
  }

  /**
   * Apply a saved order to a live nav-item array.
   * Items whose route is not in the saved order (i.e. newly added permissions)
   * are appended at the end so they always appear.
   */
  applyOrder<T extends { route: string }>(items: T[], savedOrder: string[]): T[] {
    const ordered: T[] = [];
    const remaining = new Map(items.map((i) => [i.route, i]));

    for (const route of savedOrder) {
      const item = remaining.get(route);
      if (item) {
        ordered.push(item);
        remaining.delete(route);
      }
    }

    // Append any items not covered by the saved order
    ordered.push(...remaining.values());
    return ordered;
  }
}
