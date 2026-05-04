import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, forkJoin, map, of } from 'rxjs';

import { AuditSearchFilter } from './audit-search.model';

/**
 * Server-side saved search (MVP-8c). Mirrors the backend
 * `AuditSavedSearchResponseDTO`. {@link SavedAuditSearch.filter} is
 * deserialised from the {@code filterJson} column and may include any
 * subset of {@link AuditSearchFilter} fields.
 */
export interface SavedAuditSearch {
  id: string;
  ownerUsername: string;
  name: string;
  filter: AuditSearchFilter;
  shared: boolean;
  createdAt: string;
  updatedAt: string;
}

interface SavedAuditSearchResponse {
  id: string;
  ownerUsername: string;
  name: string;
  filterJson: string;
  shared: boolean;
  createdAt: string;
  updatedAt: string;
}

interface LegacyEntry {
  id: string;
  name: string;
  filter: AuditSearchFilter;
}

const LEGACY_STORAGE_KEY = 'super_admin_audit_saved_searches';
const MIGRATION_FLAG_KEY = 'super_admin_audit_saved_searches_migrated';
const BASE = '/super-admin/audit-search/saved';

/**
 * MVP-8c — server-backed saved searches. Replaces the MVP-8b
 * localStorage-only service. On first instantiation any pre-existing
 * localStorage entries are uploaded to the server (best-effort) and
 * the legacy key is cleared so a subsequent re-load doesn't re-upload.
 *
 * <p>The migration is idempotent and tolerant of failure — if the
 * upload errors out the legacy entries stay in localStorage and the
 * shim retries on the next instantiation. A persistent `migrated`
 * flag prevents repeat uploads after a successful run.
 */
@Injectable({ providedIn: 'root' })
export class AuditSavedSearchService {
  private readonly http = inject(HttpClient);

  /**
   * One-shot upload of any legacy localStorage saved searches. Safe
   * to call multiple times; falls through to a no-op once the flag
   * is set or once the legacy key is empty.
   */
  migrateLegacyEntries(): Observable<SavedAuditSearch[]> {
    if (this.alreadyMigrated()) {
      return of([]);
    }
    const legacy = this.readLegacyEntries();
    if (legacy.length === 0) {
      this.markMigrated();
      return of([]);
    }
    type UploadResult = { ok: true; value: SavedAuditSearch } | { ok: false };
    // Each upload is wrapped in catchError so a single bad row cannot
    // error the forkJoin and roll back the whole batch (Copilot review
    // fix). Failed entries fall through as { ok: false } and are
    // dropped from the result; the legacy key stays intact for a
    // retry on the next instantiation when *every* upload fails.
    const uploads: Observable<UploadResult>[] = legacy.map((entry) => {
      try {
        return this.create(entry.name, entry.filter).pipe(
          map<SavedAuditSearch, UploadResult>((created) => ({ ok: true, value: created })),
          catchError(() => of<UploadResult>({ ok: false })),
        );
      } catch {
        // create() throws synchronously on a blank name — treat the
        // same as a failed upload so the batch still proceeds.
        return of<UploadResult>({ ok: false });
      }
    });
    return forkJoin(uploads).pipe(
      map((results) => {
        const created = results
          .filter((r): r is { ok: true; value: SavedAuditSearch } => r.ok)
          .map((r) => r.value);
        const allFailed = legacy.length > 0 && created.length === 0;
        // Clear the legacy key when at least one upload succeeded so a
        // re-run doesn't duplicate server rows. When every upload
        // failed, leave the legacy entries for a retry on the next
        // instantiation.
        if (!allFailed) {
          try {
            localStorage.removeItem(LEGACY_STORAGE_KEY);
            this.markMigrated();
          } catch {
            // QuotaExceededError / SecurityError on a sandbox profile —
            // nothing to do; next call will retry the migration.
          }
        }
        return created;
      }),
    );
  }

  list(): Observable<SavedAuditSearch[]> {
    return this.http.get<SavedAuditSearchResponse[]>(BASE).pipe(map((rows) => rows.map(toClient)));
  }

  create(name: string, filter: AuditSearchFilter, shared = false): Observable<SavedAuditSearch> {
    const trimmed = name.trim();
    if (!trimmed) {
      throw new Error('Saved-search name is required');
    }
    return this.http
      .post<SavedAuditSearchResponse>(BASE, {
        name: trimmed,
        // Pagination is not part of the persisted snapshot — re-applying
        // a saved search should always start at page 0.
        filterJson: JSON.stringify(this.stripPagination(filter)),
        shared,
      })
      .pipe(map(toClient));
  }

  update(
    id: string,
    name: string,
    filter: AuditSearchFilter,
    shared = false,
  ): Observable<SavedAuditSearch> {
    return this.http
      .put<SavedAuditSearchResponse>(`${BASE}/${encodeURIComponent(id)}`, {
        name: name.trim(),
        filterJson: JSON.stringify(this.stripPagination(filter)),
        shared,
      })
      .pipe(map(toClient));
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/${encodeURIComponent(id)}`);
  }

  // ── helpers ──────────────────────────────────────────────────────────

  private stripPagination(filter: AuditSearchFilter): AuditSearchFilter {
    // Clone-and-delete the pagination fields. Re-applying a saved
    // search should always start at page 0, so the persisted snapshot
    // never carries `page` / `size`.
    const copy: AuditSearchFilter = { ...filter };
    delete copy.page;
    delete copy.size;
    return copy;
  }

  private readLegacyEntries(): LegacyEntry[] {
    try {
      const raw = localStorage.getItem(LEGACY_STORAGE_KEY);
      if (!raw) return [];
      const parsed = JSON.parse(raw) as LegacyEntry[];
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }

  private alreadyMigrated(): boolean {
    try {
      return localStorage.getItem(MIGRATION_FLAG_KEY) === 'true';
    } catch {
      return false;
    }
  }

  private markMigrated(): void {
    try {
      localStorage.setItem(MIGRATION_FLAG_KEY, 'true');
    } catch {
      // ignore
    }
  }
}

function toClient(row: SavedAuditSearchResponse): SavedAuditSearch {
  let filter: AuditSearchFilter = {};
  try {
    filter = row.filterJson ? (JSON.parse(row.filterJson) as AuditSearchFilter) : {};
  } catch {
    // Bad JSON in the persisted column — surface an empty filter
    // rather than throwing into the component.
    filter = {};
  }
  return {
    id: row.id,
    ownerUsername: row.ownerUsername,
    name: row.name,
    filter,
    shared: row.shared,
    createdAt: row.createdAt,
    updatedAt: row.updatedAt,
  };
}
