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
  searchTerm = '';
  selectedEventType = '';

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.auditService.list({ size: 200 }).subscribe({
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
    this.applyFilter();
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
