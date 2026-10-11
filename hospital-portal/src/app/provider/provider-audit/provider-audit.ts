import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import { Subscription } from 'rxjs';

import { ProviderContextService } from '../../core/provider-context.service';

import { ProviderAuditPage, isNotAvailable } from '../../services/provider.model';
import { ProviderPortalService } from '../../services/provider-portal.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { RoleLabelPipe } from '../../shared/pipes/role-label.pipe';
import { followFacility } from '../follow-facility';

const PAGE_SIZE = 20;

/**
 * The facility's own audit trail (provider plan §3.1), for its
 * PROVIDER_ADMIN: what its staff did here, newest first. The server returns
 * ids and codes only, of facility administration only, never a row about a
 * patient. The page follows the facility the session acts at: a branch switch
 * starts again at its first page, and an answer about the facility the page
 * has left is dropped, so one branch's rows are never paged under another's.
 */
@Component({
  selector: 'app-provider-audit',
  standalone: true,
  imports: [DatePipe, TranslateModule, EnumLabelPipe, RoleLabelPipe],
  templateUrl: './provider-audit.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: '../provider-pages.scss',
})
export class ProviderAuditComponent {
  private readonly portal = inject(ProviderPortalService);
  private readonly context = inject(ProviderContextService);

  readonly page = signal<ProviderAuditPage | null>(null);
  readonly pageIndex = signal(0);
  readonly loading = signal(true);
  readonly notAvailable = signal(false);
  readonly loadFailed = signal(false);

  readonly entries = computed(() => this.page()?.entries ?? []);
  readonly hasPrevious = computed(() => this.pageIndex() > 0);
  readonly hasNext = computed(() => this.page()?.hasMore ?? false);
  /**
   * The pager stays whenever there is somewhere to go, the rows or not: an
   * empty page after the first (rows aged out, a smaller trail) keeps
   * Previous, so the user is never stranded on it.
   */
  readonly showPager = computed(
    () =>
      !this.loading() &&
      !this.notAvailable() &&
      !this.loadFailed() &&
      (this.hasPrevious() || this.hasNext()),
  );

  /** The facility the trail shows; a branch switch starts it again. */
  private readonly shownFacility = followFacility({
    load: () => {
      this.pageIndex.set(0);
      this.page.set(null);
      this.load();
    },
    cleared: () => this.leave(false),
    failed: () => this.leave(true),
  });
  private loadSub?: Subscription;

  /** The facility went away (or could not be read): nothing of it stays on screen. */
  private leave(failed: boolean): void {
    this.loadSub?.unsubscribe();
    this.page.set(null);
    this.pageIndex.set(0);
    this.loading.set(false);
    this.notAvailable.set(!failed);
    this.loadFailed.set(failed);
  }

  /**
   * Retry after a failure: the page's own read when it still shows a
   * facility, otherwise the context itself (it could not be read).
   */
  retry(): void {
    if (this.shownFacility()) {
      this.load();
    } else {
      this.context.invalidate();
    }
  }

  /** What kind of record a row acted on, in words: the entity type is a code, never shown raw. */
  recordKey(entityType: string | null): string {
    switch (entityType) {
      case 'PROVIDER_FACILITY':
        return 'PROVIDER.AUDIT.RECORD_FACILITY';
      case 'PROVIDER_STAFF':
        return 'PROVIDER.AUDIT.RECORD_STAFF';
      case 'USER_ROLE_ASSIGNMENT':
        return 'PROVIDER.AUDIT.RECORD_ASSIGNMENT';
      default:
        return 'PROVIDER.AUDIT.RECORD_OTHER';
    }
  }

  load(): void {
    this.loadSub?.unsubscribe();
    const facility = this.shownFacility();
    const pageIndex = this.pageIndex();
    const current = () => facility === this.shownFacility() && pageIndex === this.pageIndex();
    this.loading.set(true);
    this.notAvailable.set(false);
    this.loadFailed.set(false);
    this.loadSub = this.portal.audit(pageIndex, PAGE_SIZE).subscribe({
      next: (page) => {
        if (!current()) return;
        this.page.set(page);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        if (!current()) return;
        if (isNotAvailable(err)) {
          this.notAvailable.set(true);
          this.page.set(null);
          this.context.invalidate();
        } else {
          this.loadFailed.set(true);
        }
        this.loading.set(false);
      },
    });
  }

  previousPage(): void {
    if (!this.hasPrevious()) return;
    this.pageIndex.update((i) => i - 1);
    this.load();
  }

  nextPage(): void {
    if (!this.hasNext()) return;
    this.pageIndex.update((i) => i + 1);
    this.load();
  }
}
