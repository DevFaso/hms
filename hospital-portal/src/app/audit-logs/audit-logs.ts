import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  AuditLogService,
  AuditEventLog,
  AuditEventTypeStatus,
} from '../services/audit-log.service';
import { ToastService } from '../core/toast.service';
import { TranslateModule } from '@ngx-translate/core';

@Component({
  selector: 'app-audit-logs',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './audit-logs.html',
  styleUrl: './audit-logs.scss',
})
export class AuditLogsComponent implements OnInit {
  private readonly auditService = inject(AuditLogService);
  private readonly toast = inject(ToastService);

  readonly pageSize = 20;

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

  filtered = computed(() => {
    let list = this.logs();
    if (this.selectedEventType) {
      list = list.filter((l) => l.eventType === this.selectedEventType);
    }
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (l) =>
          (l.userName ?? '').toLowerCase().includes(term) ||
          (l.eventDescription ?? '').toLowerCase().includes(term) ||
          (l.entityType ?? '').toLowerCase().includes(term) ||
          (l.resourceName ?? '').toLowerCase().includes(term),
      );
    }
    return list;
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.auditService
      .list({
        page: this.currentPage(),
        size: this.pageSize,
        fromDate: this.fromDate || undefined,
        toDate: this.toDate || undefined,
      })
      .subscribe({
        next: (res) => {
          this.logs.set(res?.content ?? []);
          this.totalElements.set(res?.totalElements ?? 0);
          this.totalPages.set(res?.totalPages ?? 0);
          this.loading.set(false);
        },
        error: () => {
          this.toast.error('Failed to load audit logs');
          this.loading.set(false);
        },
      });
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
    // event type filter stays client-side within the current page
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
