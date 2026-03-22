import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  AuditLogService,
  AuditEventLog,
  AuditEventTypeStatus,
} from '../services/audit-log.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-audit-logs',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './audit-logs.html',
  styleUrl: './audit-logs.scss',
})
export class AuditLogsComponent implements OnInit {
  private readonly auditService = inject(AuditLogService);
  private readonly toast = inject(ToastService);

  logs = signal<AuditEventLog[]>([]);
  filtered = signal<AuditEventLog[]>([]);
  eventTypes = signal<AuditEventTypeStatus[]>([]);
  loading = signal(true);
  exporting = signal(false);
  searchTerm = '';
  selectedEventType = '';
  fromDate = '';
  toDate = '';

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    const params: Record<string, unknown> = { size: 200 };
    if (this.fromDate) params['fromDate'] = this.fromDate;
    if (this.toDate) params['toDate'] = this.toDate;
    if (this.selectedEventType) params['eventType'] = this.selectedEventType;

    this.auditService.list(params as Parameters<typeof this.auditService.list>[0]).subscribe({
      next: (res) => {
        const list = res?.content ?? [];
        this.logs.set(list);
        this.applyFilter();
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

  applyFilter(): void {
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
    this.filtered.set(list);
  }

  onEventTypeChange(): void {
    this.load();
  }

  onDateChange(): void {
    if (this.fromDate && this.toDate) {
      this.load();
    }
  }

  clearDateFilters(): void {
    this.fromDate = '';
    this.toDate = '';
    this.load();
  }

  exportCsv(): void {
    const from = this.fromDate || new Date(Date.now() - 30 * 86400000).toISOString().slice(0, 10);
    const to = this.toDate || new Date().toISOString().slice(0, 10);

    this.exporting.set(true);
    this.auditService.exportCsv(from, to).subscribe({
      next: (blob) => {
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `audit-logs-${from}-to-${to}.csv`;
        a.click();
        window.URL.revokeObjectURL(url);
        this.exporting.set(false);
        this.toast.success('Audit logs exported successfully');
      },
      error: () => {
        this.exporting.set(false);
        this.toast.error('Failed to export audit logs');
      },
    });
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
