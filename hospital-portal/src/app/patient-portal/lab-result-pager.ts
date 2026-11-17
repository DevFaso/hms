import { signal } from '@angular/core';
import { Observable } from 'rxjs';

import { LabResultPage, LabResultSummary } from '../services/patient-portal.service';
import { refusalReason } from '../shared/refusal-reason';

/**
 * QA D1213 (round 23): the patient's lab results, page by page. `/my-lab-results`
 * read twenty rows once and showed no way to reach the older ones; a critical
 * K 6,8 was among the rows a patient could never see. The proxy's Labs tab
 * read fifty the same way.
 *
 * Holds what has been read so far and whether more exists; `loadMore()` reads
 * the next page from the offset of what is already shown. A row already shown
 * is never added twice, and a page that adds nothing ends the paging (a server
 * that ignores `offset` repeats its first page: the list stops, it does not
 * grow duplicates). A failed read keeps what is shown and records the server's
 * reason (a 4xx), or `null` for the caller's own message.
 */
export class LabResultPager {
  readonly results = signal<LabResultSummary[]>([]);
  readonly loading = signal(false);
  readonly hasMore = signal(false);
  /** The first page failed: the page shows the failure, never "no results". */
  readonly loadFailed = signal(false);
  /** A later page failed: what is shown stays, with a retry. */
  readonly loadMoreFailed = signal(false);
  /** The server's reason for the last failure, when it gave one. */
  readonly failureReason = signal<string | null>(null);

  constructor(private readonly fetch: (offset: number) => Observable<LabResultPage>) {}

  /** Reads the first page (again, after a failure). */
  reload(onLoaded?: (fresh: LabResultSummary[]) => void, onFailed?: () => void): void {
    this.results.set([]);
    this.hasMore.set(false);
    this.read(0, onLoaded, onFailed);
  }

  /** Reads the page after the rows already shown. */
  loadMore(onLoaded?: (fresh: LabResultSummary[]) => void): void {
    if (this.loading() || !this.hasMore()) return;
    this.read(this.results().length, onLoaded);
  }

  private read(
    offset: number,
    onLoaded?: (fresh: LabResultSummary[]) => void,
    onFailed?: () => void,
  ): void {
    this.loading.set(true);
    this.loadFailed.set(false);
    this.loadMoreFailed.set(false);
    this.failureReason.set(null);
    this.fetch(offset).subscribe({
      next: (page) => {
        const known = new Set(this.results().map((r) => r.id));
        const fresh = page.items.filter((r) => !known.has(r.id));
        this.results.update((list) => [...list, ...fresh]);
        this.hasMore.set(page.hasMore && (offset === 0 || fresh.length > 0));
        this.loading.set(false);
        onLoaded?.(fresh);
      },
      error: (err: unknown) => {
        this.failureReason.set(refusalReason(err));
        if (offset === 0) this.loadFailed.set(true);
        else this.loadMoreFailed.set(true);
        this.loading.set(false);
        onFailed?.();
      },
    });
  }
}
