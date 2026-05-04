import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { catchError, of } from 'rxjs';

import { AuditSearchService } from '../../services/audit-search.service';
import {
  AggregatedAuditEvent,
  AggregatedAuditPage,
  AuditSearchFilter,
  AuditSearchPage,
  AuditSearchRow,
  AuditSource,
} from '../../services/audit-search.model';
import { DataResidencyService } from '../../services/data-residency.service';
import { OrganizationRegion } from '../../services/data-residency.model';
import {
  AuditSavedSearchService,
  SavedAuditSearch,
} from '../../services/audit-saved-search.service';

const DEFAULT_PAGE_SIZE = 25;
const ALL_SOURCES: AuditSource[] = ['SUPPORT', 'FRONTEND', 'PERMISSION_MATRIX'];

type ActiveTab = 'support' | 'aggregated';

@Component({
  selector: 'app-super-admin-audit-search',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, TranslateModule, DatePipe],
  templateUrl: './audit-search.html',
  styleUrl: './audit-search.scss',
})
export class SuperAdminAuditSearchComponent implements OnInit {
  private readonly service = inject(AuditSearchService);
  private readonly residencyService = inject(DataResidencyService);
  private readonly savedSearchService = inject(AuditSavedSearchService);

  readonly loading = signal(false);
  readonly errored = signal(false);
  readonly page = signal<AuditSearchPage | null>(null);

  readonly userName = signal('');
  readonly impersonatorUserId = signal('');
  readonly entityType = signal('');
  readonly resourceId = signal('');
  readonly status = signal('');
  readonly fromDate = signal('');
  readonly toDate = signal('');
  readonly tenantRegion = signal<OrganizationRegion | ''>('');
  readonly availableRegions = signal<OrganizationRegion[]>([]);
  readonly pageNumber = signal(0);

  readonly rows = computed<AuditSearchRow[]>(() => this.page()?.content ?? []);
  readonly totalElements = computed(() => this.page()?.totalElements ?? 0);
  readonly totalPages = computed(() => this.page()?.totalPages ?? 0);

  readonly hasPrev = computed(() => this.pageNumber() > 0);
  readonly hasNext = computed(() => this.pageNumber() < this.totalPages() - 1);

  readonly statusOptions = ['SUCCESS', 'FAILURE', 'PENDING'];
  readonly exporting = signal(false);
  readonly exportError = signal<string | null>(null);

  // ── MVP-8c — server-backed saved searches ──────────────────────────
  readonly savedSearches = signal<SavedAuditSearch[]>([]);
  readonly newSavedSearchName = signal('');
  readonly newSavedSearchShared = signal(false);
  readonly saveError = signal<string | null>(null);

  // ── MVP-8c — aggregation tab ───────────────────────────────────────
  /** Exposed for the template's @for over source toggles. */
  readonly allSources: readonly AuditSource[] = ALL_SOURCES;
  readonly activeTab = signal<ActiveTab>('support');
  readonly aggregatedSources = signal<AuditSource[]>([...ALL_SOURCES]);
  readonly aggregatedPage = signal<AggregatedAuditPage | null>(null);
  readonly aggregatedPageNumber = signal(0);
  readonly aggregatedLoading = signal(false);
  readonly aggregatedError = signal(false);

  readonly aggregatedRows = computed<AggregatedAuditEvent[]>(
    () => this.aggregatedPage()?.content ?? [],
  );
  readonly aggregatedTotalPages = computed(() => this.aggregatedPage()?.totalPages ?? 0);
  readonly aggregatedHasPrev = computed(() => this.aggregatedPageNumber() > 0);
  readonly aggregatedHasNext = computed(
    () => this.aggregatedPageNumber() < this.aggregatedTotalPages() - 1,
  );

  ngOnInit(): void {
    this.residencyService
      .listAvailableRegions()
      .pipe(catchError(() => of([] as OrganizationRegion[])))
      .subscribe((regions) => this.availableRegions.set(regions));

    // MVP-8c — upload any pre-existing localStorage saved searches to
    // the server (one-shot, idempotent), then load the merged list.
    this.savedSearchService
      .migrateLegacyEntries()
      .pipe(catchError(() => of([] as SavedAuditSearch[])))
      .subscribe(() => this.refreshSavedSearches());

    this.runSearch();
  }

  applyFilters(): void {
    this.pageNumber.set(0);
    this.runSearch();
  }

  resetFilters(): void {
    this.userName.set('');
    this.impersonatorUserId.set('');
    this.entityType.set('');
    this.resourceId.set('');
    this.status.set('');
    this.fromDate.set('');
    this.toDate.set('');
    this.tenantRegion.set('');
    this.pageNumber.set(0);
    this.runSearch();
  }

  goToPrev(): void {
    if (this.hasPrev()) {
      this.pageNumber.update((n) => n - 1);
      this.runSearch();
    }
  }

  goToNext(): void {
    if (this.hasNext()) {
      this.pageNumber.update((n) => n + 1);
      this.runSearch();
    }
  }

  runSearch(): void {
    this.loading.set(true);
    this.errored.set(false);

    this.service
      .search({ ...this.currentFilterSnapshot(), page: this.pageNumber(), size: DEFAULT_PAGE_SIZE })
      .pipe(
        catchError(() => {
          this.errored.set(true);
          return of(null);
        }),
      )
      .subscribe((page) => {
        this.page.set(page);
        this.loading.set(false);
      });
  }

  // ── MVP-8c aggregation tab ──────────────────────────────────────────

  selectTab(tab: ActiveTab): void {
    this.activeTab.set(tab);
    if (tab === 'aggregated' && this.aggregatedPage() === null) {
      this.runAggregatedSearch();
    }
  }

  toggleAggregatedSource(source: AuditSource): void {
    this.aggregatedSources.update((current) => {
      if (current.includes(source)) {
        // Copilot review fix — block deselecting the last source.
        // Backend treats an empty `sources` list as "all three", so an
        // operator who unchecks everything would silently get the
        // opposite of what they expect; refusing the toggle keeps the
        // checkbox state honest.
        if (current.length === 1) {
          return current;
        }
        return current.filter((s) => s !== source);
      }
      return [...current, source];
    });
  }

  /** True when the source is the only remaining selection. */
  isLastActiveSource(source: AuditSource): boolean {
    const active = this.aggregatedSources();
    return active.length === 1 && active[0] === source;
  }

  isSourceActive(source: AuditSource): boolean {
    return this.aggregatedSources().includes(source);
  }

  applyAggregatedFilters(): void {
    this.aggregatedPageNumber.set(0);
    this.runAggregatedSearch();
  }

  goToAggregatedPrev(): void {
    if (this.aggregatedHasPrev()) {
      this.aggregatedPageNumber.update((n) => n - 1);
      this.runAggregatedSearch();
    }
  }

  goToAggregatedNext(): void {
    if (this.aggregatedHasNext()) {
      this.aggregatedPageNumber.update((n) => n + 1);
      this.runAggregatedSearch();
    }
  }

  runAggregatedSearch(): void {
    this.aggregatedLoading.set(true);
    this.aggregatedError.set(false);
    this.service
      .searchAggregated({
        sources: this.aggregatedSources(),
        fromDate: this.toIsoDateTime(this.fromDate()),
        toDate: this.toIsoDateTime(this.toDate()),
        page: this.aggregatedPageNumber(),
        size: DEFAULT_PAGE_SIZE,
      })
      .pipe(
        catchError(() => {
          this.aggregatedError.set(true);
          return of(null);
        }),
      )
      .subscribe((page) => {
        this.aggregatedPage.set(page);
        this.aggregatedLoading.set(false);
      });
  }

  // ── helpers ─────────────────────────────────────────────────────────

  private trimOrUndefined(value: string): string | undefined {
    const v = value?.trim();
    return v && v.length > 0 ? v : undefined;
  }

  private toIsoDateTime(value: string): string | undefined {
    const v = this.trimOrUndefined(value);
    if (!v) return undefined;
    return v.length === 16 ? `${v}:00` : v;
  }

  exportCsv(): void {
    if (this.exporting()) return;
    this.exporting.set(true);
    this.exportError.set(null);
    this.service
      .exportCsv(this.currentFilterSnapshot())
      .pipe(
        catchError(() => {
          this.exportError.set('AUDIT_SEARCH.EXPORT.FAILED');
          return of(null);
        }),
      )
      .subscribe((blob) => {
        this.exporting.set(false);
        if (!blob) return;
        this.triggerBrowserDownload(blob, 'audit-search.csv');
      });
  }

  private currentFilterSnapshot(): AuditSearchFilter {
    return {
      userName: this.trimOrUndefined(this.userName()),
      impersonatorUserId: this.trimOrUndefined(this.impersonatorUserId()),
      entityType: this.trimOrUndefined(this.entityType()),
      resourceId: this.trimOrUndefined(this.resourceId()),
      status: this.trimOrUndefined(this.status()),
      fromDate: this.toIsoDateTime(this.fromDate()),
      toDate: this.toIsoDateTime(this.toDate()),
      tenantRegion: this.tenantRegion() === '' ? undefined : this.tenantRegion(),
    };
  }

  // ── MVP-8c saved searches (REST) ───────────────────────────────────

  refreshSavedSearches(): void {
    this.savedSearchService
      .list()
      .pipe(catchError(() => of([] as SavedAuditSearch[])))
      .subscribe((list) => this.savedSearches.set(list));
  }

  saveCurrent(): void {
    const name = this.newSavedSearchName().trim();
    if (!name) {
      this.saveError.set('AUDIT_SEARCH.SAVED.NAME_REQUIRED');
      return;
    }
    this.savedSearchService
      .create(name, this.currentFilterSnapshot(), this.newSavedSearchShared())
      .pipe(
        catchError(() => {
          this.saveError.set('AUDIT_SEARCH.SAVED.SAVE_FAILED');
          return of(null);
        }),
      )
      .subscribe((created) => {
        if (!created) return;
        this.savedSearches.update((current) => [created, ...current]);
        this.newSavedSearchName.set('');
        this.newSavedSearchShared.set(false);
        this.saveError.set(null);
      });
  }

  applySaved(saved: SavedAuditSearch): void {
    const f = saved.filter;
    this.userName.set(f.userName ?? '');
    this.impersonatorUserId.set(f.impersonatorUserId ?? '');
    this.entityType.set(f.entityType ?? '');
    this.resourceId.set(f.resourceId ?? '');
    this.status.set(f.status ?? '');
    this.fromDate.set(this.fromIsoForInput(f.fromDate));
    this.toDate.set(this.fromIsoForInput(f.toDate));
    this.tenantRegion.set((f.tenantRegion as OrganizationRegion | undefined) ?? '');
    this.pageNumber.set(0);
    this.runSearch();
  }

  deleteSaved(saved: SavedAuditSearch): void {
    this.savedSearchService
      .delete(saved.id)
      .pipe(catchError(() => of(null)))
      .subscribe(() =>
        this.savedSearches.update((current) => current.filter((s) => s.id !== saved.id)),
      );
  }

  private fromIsoForInput(value: string | undefined): string {
    if (!value) return '';
    return value.length === 19 && value.endsWith(':00') ? value.substring(0, 16) : value;
  }

  private triggerBrowserDownload(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }

  statusPillClass(status: string | null): string {
    const normalised = (status ?? '').toUpperCase();
    if (normalised === 'SUCCESS' || normalised === 'COMPLETED') {
      return 'status-pill pill-success';
    }
    if (normalised === 'FAILURE' || normalised === 'FAILED' || normalised === 'ERROR') {
      return 'status-pill pill-fail';
    }
    if (normalised === 'PENDING' || normalised === 'IN_PROGRESS') {
      return 'status-pill pill-pending';
    }
    return 'status-pill pill-neutral';
  }
}
