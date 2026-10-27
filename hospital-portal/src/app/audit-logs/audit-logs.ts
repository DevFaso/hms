import { Component, inject, OnInit, signal, ChangeDetectionStrategy } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, Subscription, debounceTime, distinctUntilChanged } from 'rxjs';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  AuditLogService,
  AuditEventLog,
  AuditEventTypeStatus,
} from '../services/audit-log.service';
import { ToastService } from '../core/toast.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { RoleLabelPipe } from '../shared/pipes/role-label.pipe';
import { AuditDescriptionService } from './audit-description.service';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { toServerDateTime } from '../shared/date-utils';

/** QA D794: how long the search box waits after the last keystroke before asking the server. */
const SEARCH_DEBOUNCE_MS = 300;

@Component({
  selector: 'app-audit-logs',
  standalone: true,
  imports: [
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    EnumLabelPipe,
    RoleLabelPipe,
  ],
  templateUrl: './audit-logs.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './audit-logs.scss',
})
export class AuditLogsComponent implements OnInit {
  private readonly auditService = inject(AuditLogService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly descriptions = inject(AuditDescriptionService);

  readonly pageSize = 20;

  /** QA D330: the entity in the reader's language. */
  entityLabel(entityType: string | null | undefined): string {
    return this.descriptions.entityLabel(entityType);
  }

  /** QA D330 / D774 / D894: the description in the reader's language. */
  describe(log: AuditEventLog): string {
    return this.descriptions.describe(log);
  }

  logs = signal<AuditEventLog[]>([]);
  eventTypes = signal<AuditEventTypeStatus[]>([]);
  loading = signal(true);
  currentPage = signal(0);
  totalElements = signal(0);
  totalPages = signal(0);

  searchTerm = '';
  selectedEventType = '';
  fromDate = '';
  toDate = '';

  /**
   * QA D794: the search box and the event-type filter are applied by the
   * server over every audit row the reader may see. They used to filter the
   * 20 rows on screen, so a match on another page was never found.
   */
  private readonly searchChanges = new Subject<string>();
  private loadSub?: Subscription;

  constructor() {
    this.searchChanges
      .pipe(debounceTime(SEARCH_DEBOUNCE_MS), distinctUntilChanged(), takeUntilDestroyed())
      .subscribe(() => {
        this.currentPage.set(0);
        this.load();
      });
  }

  ngOnInit(): void {
    this.load();
    this.loadEventTypes();
  }

  load(): void {
    this.loading.set(true);
    const search = this.searchTerm.trim();
    // A newer query replaces an older one still in flight, so a slow page
    // never overwrites the result of the latest search.
    this.loadSub?.unsubscribe();
    this.loadSub = this.auditService
      .list({
        page: this.currentPage(),
        size: this.pageSize,
        // QA D777/D784: the picker gives local wall time; the server keeps UTC.
        fromDate: this.fromDate ? toServerDateTime(this.fromDate) : undefined,
        toDate: this.toDate ? toServerDateTime(this.toDate) : undefined,
        eventType: this.selectedEventType || undefined,
        search: search || undefined,
      })
      .subscribe({
        next: (res) => {
          this.logs.set(res?.content ?? []);
          this.totalElements.set(res?.totalElements ?? 0);
          this.totalPages.set(res?.totalPages ?? 0);
          this.loading.set(false);
        },
        error: () => {
          this.toast.error(this.translate.instant('AUDIT.LOAD_FAILED'));
          this.loading.set(false);
        },
      });
  }

  private loadEventTypes(): void {
    this.auditService.getEventTypeStatus().subscribe({
      next: (types) => this.eventTypes.set(Array.isArray(types) ? types : []),
      error: () => {
        /* event types optional */
      },
    });
  }

  applyDateFilter(): void {
    this.currentPage.set(0);
    this.load();
  }

  clearDateFilter(): void {
    this.fromDate = '';
    this.toDate = '';
    this.currentPage.set(0);
    this.load();
  }

  onEventTypeChange(): void {
    this.currentPage.set(0);
    this.load();
  }

  onSearchInput(): void {
    this.searchChanges.next(this.searchTerm.trim());
  }

  prevPage(): void {
    if (this.currentPage() > 0) {
      this.currentPage.update((p) => p - 1);
      this.load();
    }
  }

  nextPage(): void {
    if (this.currentPage() < this.totalPages() - 1) {
      this.currentPage.update((p) => p + 1);
      this.load();
    }
  }

  getStatusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'SUCCESS':
        return 'status-success';
      case 'FAILURE':
      case 'ERROR':
        return 'status-failure';
      default:
        return 'status-info';
    }
  }
}
