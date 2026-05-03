import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { catchError, of } from 'rxjs';

import { AuditSearchService } from '../../services/audit-search.service';
import {
  AuditSearchFilter,
  AuditSearchPage,
  AuditSearchRow,
} from '../../services/audit-search.model';
import { DataResidencyService } from '../../services/data-residency.service';
import { OrganizationRegion } from '../../services/data-residency.model';
import {
  AuditSavedSearchService,
  SavedAuditSearch,
} from '../../services/audit-saved-search.service';

const DEFAULT_PAGE_SIZE = 25;

@Component({
  selector: 'app-super-admin-audit-search',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, TranslateModule, DatePipe],
  templateUrl: './audit-search.html',
  styleUrl: './audit-search.scss',
})
export class SuperAdminAuditSearchComponent implements OnInit {
  private readonly service = inject(AuditSearchService);
  // MVP-9b — drives the tenant-region filter dropdown without hardcoding
  // the enum. Same source as the Data Residency console so a new code
  // is one backend addition, not a parallel frontend change.
  private readonly residencyService = inject(DataResidencyService);
  // MVP-8b — localStorage-backed saved searches per operator.
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
  // MVP-8b — CSV export busy/error state distinct from the JSON search.
  readonly exporting = signal(false);
  readonly exportError = signal<string | null>(null);
  // MVP-8b — saved searches surfaced as a dropdown above the filter form.
  readonly savedSearches = signal<SavedAuditSearch[]>([]);
  readonly newSavedSearchName = signal('');
  readonly saveError = signal<string | null>(null);

  ngOnInit(): void {
    // MVP-9b — load the region catalogue once on mount; failure leaves
    // the dropdown empty (the search still works without the filter).
    this.residencyService
      .listAvailableRegions()
      .pipe(catchError(() => of([] as OrganizationRegion[])))
      .subscribe((regions) => this.availableRegions.set(regions));
    // MVP-8b — hydrate saved searches from localStorage.
    this.savedSearches.set(this.savedSearchService.list());
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

    const filter: AuditSearchFilter = {
      userName: this.trimOrUndefined(this.userName()),
      impersonatorUserId: this.trimOrUndefined(this.impersonatorUserId()),
      entityType: this.trimOrUndefined(this.entityType()),
      resourceId: this.trimOrUndefined(this.resourceId()),
      status: this.trimOrUndefined(this.status()),
      fromDate: this.toIsoDateTime(this.fromDate()),
      toDate: this.toIsoDateTime(this.toDate()),
      tenantRegion: this.tenantRegion() === '' ? undefined : this.tenantRegion(),
      page: this.pageNumber(),
      size: DEFAULT_PAGE_SIZE,
    };

    this.service
      .search(filter)
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

  private trimOrUndefined(value: string): string | undefined {
    const v = value?.trim();
    return v && v.length > 0 ? v : undefined;
  }

  // Datetime-local inputs return "YYYY-MM-DDTHH:mm"; backend wants ISO_DATE_TIME
  // (which accepts that shape, but normalising to seconds keeps it predictable).
  private toIsoDateTime(value: string): string | undefined {
    const v = this.trimOrUndefined(value);
    if (!v) return undefined;
    return v.length === 16 ? `${v}:00` : v;
  }

  /**
   * MVP-8b — fetch the same filter as a CSV blob and trigger a browser
   * download via a temporary object URL. Reuses the live filter signals
   * so the export matches whatever the user is currently viewing.
   */
  exportCsv(): void {
    if (this.exporting()) return;
    this.exporting.set(true);
    this.exportError.set(null);
    const filter: AuditSearchFilter = {
      userName: this.trimOrUndefined(this.userName()),
      impersonatorUserId: this.trimOrUndefined(this.impersonatorUserId()),
      entityType: this.trimOrUndefined(this.entityType()),
      resourceId: this.trimOrUndefined(this.resourceId()),
      status: this.trimOrUndefined(this.status()),
      fromDate: this.toIsoDateTime(this.fromDate()),
      toDate: this.toIsoDateTime(this.toDate()),
      tenantRegion: this.tenantRegion() === '' ? undefined : this.tenantRegion(),
    };
    this.service
      .exportCsv(filter)
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

  /**
   * MVP-8b — snapshot the *current* filter signals into a persistable
   * AuditSearchFilter (no pagination — the saved-search service strips
   * page/size anyway, but generating without them keeps the snapshot
   * clean for a future server-side persistence).
   */
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

  saveCurrent(): void {
    const name = this.newSavedSearchName().trim();
    if (!name) {
      this.saveError.set('AUDIT_SEARCH.SAVED.NAME_REQUIRED');
      return;
    }
    try {
      this.savedSearchService.save(name, this.currentFilterSnapshot());
      this.savedSearches.set(this.savedSearchService.list());
      this.newSavedSearchName.set('');
      this.saveError.set(null);
    } catch {
      this.saveError.set('AUDIT_SEARCH.SAVED.SAVE_FAILED');
    }
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
    this.savedSearchService.delete(saved.id);
    this.savedSearches.set(this.savedSearchService.list());
  }

  /** Strip the trailing `:00` we add in toIsoDateTime so the input
   *  re-renders cleanly when a saved search is re-applied. */
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

  /**
   * PR #228 review — status pill colour now distinguishes SUCCESS,
   * FAILURE/ERROR, PENDING/IN_PROGRESS, and unknown values instead of
   * collapsing every non-SUCCESS into the failure style.
   */
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
