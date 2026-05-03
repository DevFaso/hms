import { Injectable } from '@angular/core';

import { AuditSearchFilter } from './audit-search.model';

export interface SavedAuditSearch {
  id: string;
  name: string;
  filter: AuditSearchFilter;
  createdAt: string;
}

const STORAGE_KEY = 'super_admin_audit_saved_searches';
const MAX_SAVED = 25;

/**
 * MVP-8b — per-operator saved searches for the cross-tenant audit
 * search. Stored in localStorage so the operator's bookmarks survive
 * tab restarts without a server round-trip. Server-side persistence
 * (cross-device sync, share with other super admins) is deferred to
 * MVP-8c — the contract here is intentionally narrow so a future
 * service-backed implementation can drop in without touching the
 * audit-search component.
 *
 * <p>Storage cap: {@link #MAX_SAVED} entries. New entries past the
 * cap evict the oldest (FIFO) so a runaway script can't bloat
 * localStorage.
 */
@Injectable({ providedIn: 'root' })
export class AuditSavedSearchService {
  list(): SavedAuditSearch[] {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return [];
      const parsed = JSON.parse(raw) as SavedAuditSearch[];
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      // Corrupt JSON / SecurityError on a partitioned origin → return
      // an empty list rather than throwing into the component.
      return [];
    }
  }

  save(name: string, filter: AuditSearchFilter): SavedAuditSearch {
    const trimmed = name.trim();
    if (!trimmed) {
      throw new Error('Saved-search name is required');
    }
    const entries = this.list();
    // Same name → overwrite in place (newest filter wins for that label).
    const existingIdx = entries.findIndex((e) => e.name === trimmed);
    const entry: SavedAuditSearch = {
      id: crypto.randomUUID(),
      name: trimmed,
      // Strip pagination from the snapshot — saving "page 7" makes no
      // sense once the underlying data has shifted.
      filter: this.stripPagination(filter),
      createdAt: new Date().toISOString(),
    };
    if (existingIdx >= 0) {
      entries[existingIdx] = entry;
    } else {
      entries.unshift(entry);
    }
    // FIFO eviction past the cap.
    const capped = entries.slice(0, MAX_SAVED);
    this.persist(capped);
    return entry;
  }

  delete(id: string): void {
    const remaining = this.list().filter((e) => e.id !== id);
    this.persist(remaining);
  }

  private stripPagination(filter: AuditSearchFilter): AuditSearchFilter {
    const { page: _omitPage, size: _omitSize, ...rest } = filter;
    void _omitPage;
    void _omitSize;
    return rest;
  }

  private persist(entries: SavedAuditSearch[]): void {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(entries));
    } catch {
      // QuotaExceededError on a sandbox profile → nothing to do; the
      // caller already has the in-memory list and a future save() will
      // retry. Failing silently here matches the get-side tolerance.
    }
  }
}
