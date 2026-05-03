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
  readonly pageNumber = signal(0);

  readonly rows = computed<AuditSearchRow[]>(() => this.page()?.content ?? []);
  readonly totalElements = computed(() => this.page()?.totalElements ?? 0);
  readonly totalPages = computed(() => this.page()?.totalPages ?? 0);

  readonly hasPrev = computed(() => this.pageNumber() > 0);
  readonly hasNext = computed(() => this.pageNumber() < this.totalPages() - 1);

  readonly statusOptions = ['SUCCESS', 'FAILURE', 'PENDING'];

  ngOnInit(): void {
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
}
