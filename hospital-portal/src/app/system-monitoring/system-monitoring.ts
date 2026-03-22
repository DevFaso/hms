import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  SystemMonitoringService,
  SystemAlertDTO,
  AlertSummary,
} from '../services/system-monitoring.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-system-monitoring',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './system-monitoring.html',
  styleUrl: './system-monitoring.scss',
})
export class SystemMonitoringComponent implements OnInit {
  private readonly monitoringService = inject(SystemMonitoringService);
  private readonly toast = inject(ToastService);

  loading = signal(true);
  alerts = signal<SystemAlertDTO[]>([]);
  summary = signal<AlertSummary | null>(null);
  severityFilter = signal('');
  page = signal(0);
  totalPages = signal(0);
  totalElements = signal(0);

  summaryCards = computed(() => {
    const s = this.summary();
    if (!s) return [];
    return [
      { label: 'Total Alerts', value: s.total, icon: 'notifications', color: '#3b82f6' },
      { label: 'Unacknowledged', value: s.unacknowledged, icon: 'priority_high', color: '#f59e0b' },
      { label: 'Critical', value: s.critical, icon: 'error', color: '#ef4444' },
      { label: 'High', value: s.high, icon: 'warning', color: '#f97316' },
      { label: 'Medium', value: s.medium, icon: 'info', color: '#3b82f6' },
      { label: 'Last 24h', value: s.last24h, icon: 'schedule', color: '#8b5cf6' },
    ];
  });

  ngOnInit(): void {
    this.loadSummary();
    this.loadAlerts();
  }

  loadSummary(): void {
    this.monitoringService.getAlertSummary().subscribe({
      next: (s) => this.summary.set(s),
      error: () => this.toast.error('Failed to load alert summary'),
    });
  }

  loadAlerts(): void {
    this.loading.set(true);
    const severity = this.severityFilter() || undefined;
    this.monitoringService.listAlerts(this.page(), 20, severity).subscribe({
      next: (result) => {
        this.alerts.set(result.content);
        this.totalPages.set(result.totalPages);
        this.totalElements.set(result.totalElements);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load alerts');
        this.loading.set(false);
      },
    });
  }

  onFilterChange(): void {
    this.page.set(0);
    this.loadAlerts();
  }

  prevPage(): void {
    if (this.page() > 0) {
      this.page.update((p) => p - 1);
      this.loadAlerts();
    }
  }

  nextPage(): void {
    if (this.page() < this.totalPages() - 1) {
      this.page.update((p) => p + 1);
      this.loadAlerts();
    }
  }

  acknowledge(alert: SystemAlertDTO): void {
    this.monitoringService.acknowledgeAlert(alert.id).subscribe({
      next: (updated) => {
        this.alerts.update((list) => list.map((a) => (a.id === updated.id ? updated : a)));
        this.loadSummary();
        this.toast.success('Alert acknowledged');
      },
      error: () => this.toast.error('Failed to acknowledge alert'),
    });
  }

  resolve(alert: SystemAlertDTO): void {
    this.monitoringService.resolveAlert(alert.id).subscribe({
      next: (updated) => {
        this.alerts.update((list) => list.map((a) => (a.id === updated.id ? updated : a)));
        this.loadSummary();
        this.toast.success('Alert resolved');
      },
      error: () => this.toast.error('Failed to resolve alert'),
    });
  }

  getSeverityClass(severity: string): string {
    switch (severity?.toUpperCase()) {
      case 'CRITICAL':
        return 'severity-critical';
      case 'HIGH':
        return 'severity-high';
      case 'MEDIUM':
        return 'severity-medium';
      default:
        return 'severity-low';
    }
  }
}
