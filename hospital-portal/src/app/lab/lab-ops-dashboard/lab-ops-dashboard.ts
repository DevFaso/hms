import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { DashboardService, LabOpsSummary } from '../../services/dashboard.service';
import { ToastService } from '../../core/toast.service';

interface StatCard {
  key: string;
  label: string;
  value: string | number;
  icon: string;
  color: string;
  bgColor: string;
}

interface StatusRow {
  label: string;
  count: number;
  color: string;
  pct: number;
}

@Component({
  selector: 'app-lab-ops-dashboard',
  standalone: true,
  imports: [CommonModule, RouterModule],
  templateUrl: './lab-ops-dashboard.html',
  styleUrl: './lab-ops-dashboard.scss',
})
export class LabOpsDashboardComponent implements OnInit {
  private readonly dashboardService = inject(DashboardService);
  private readonly toast = inject(ToastService);

  loading = signal(true);
  summary = signal<LabOpsSummary | null>(null);

  // ── KPI stat cards ────────────────────────────────────────────
  statCards = computed<StatCard[]>(() => {
    const s = this.summary();
    if (!s) return [];

    const avgTat =
      s.avgTurnaroundMinutesToday !== null && s.avgTurnaroundMinutesToday !== undefined
        ? Math.round(s.avgTurnaroundMinutesToday) + ' min'
        : 'N/A';

    return [
      {
        key: 'orders_today',
        label: 'Orders Today',
        value: s.ordersToday,
        icon: 'science',
        color: '#2563eb',
        bgColor: '#dbeafe',
      },
      {
        key: 'completed_today',
        label: 'Completed Today',
        value: s.completedToday,
        icon: 'check_circle',
        color: '#059669',
        bgColor: '#d1fae5',
      },
      {
        key: 'avg_tat',
        label: 'Avg TAT (Today)',
        value: avgTat,
        icon: 'timer',
        color: '#d97706',
        bgColor: '#fef3c7',
      },
      {
        key: 'in_progress',
        label: 'In Progress',
        value: s.statusInProgress,
        icon: 'pending_actions',
        color: '#7c3aed',
        bgColor: '#ede9fe',
      },
      {
        key: 'orders_week',
        label: 'Orders This Week',
        value: s.ordersThisWeek,
        icon: 'date_range',
        color: '#0891b2',
        bgColor: '#cffafe',
      },
      {
        key: 'aging',
        label: 'Aging (>24h)',
        value: s.ordersOlderThan24h,
        icon: 'warning',
        color: s.ordersOlderThan24h > 0 ? '#dc2626' : '#64748b',
        bgColor: s.ordersOlderThan24h > 0 ? '#fee2e2' : '#f1f5f9',
      },
    ];
  });

  // ── Status breakdown rows ─────────────────────────────────────
  statusRows = computed<StatusRow[]>(() => {
    const s = this.summary();
    if (!s) return [];

    const totalActive =
      s.statusOrdered +
      s.statusPending +
      s.statusCollected +
      s.statusReceived +
      s.statusInProgress +
      s.statusResulted +
      s.statusVerified;

    const pct = (v: number) => (totalActive > 0 ? Math.round((v / totalActive) * 100) : 0);

    return [
      { label: 'Ordered', count: s.statusOrdered, color: '#6366f1', pct: pct(s.statusOrdered) },
      { label: 'Pending', count: s.statusPending, color: '#f59e0b', pct: pct(s.statusPending) },
      {
        label: 'Collected',
        count: s.statusCollected,
        color: '#06b6d4',
        pct: pct(s.statusCollected),
      },
      {
        label: 'Received',
        count: s.statusReceived,
        color: '#8b5cf6',
        pct: pct(s.statusReceived),
      },
      {
        label: 'In Progress',
        count: s.statusInProgress,
        color: '#3b82f6',
        pct: pct(s.statusInProgress),
      },
      {
        label: 'Resulted',
        count: s.statusResulted,
        color: '#10b981',
        pct: pct(s.statusResulted),
      },
      {
        label: 'Verified',
        count: s.statusVerified,
        color: '#059669',
        pct: pct(s.statusVerified),
      },
    ];
  });

  // ── Priority breakdown ────────────────────────────────────────
  priorityRows = computed(() => {
    const s = this.summary();
    if (!s) return [];
    const total = s.priorityRoutine + s.priorityUrgent + s.priorityStat;
    const pct = (v: number) => (total > 0 ? Math.round((v / total) * 100) : 0);
    return [
      {
        label: 'Routine',
        count: s.priorityRoutine,
        color: '#64748b',
        pct: pct(s.priorityRoutine),
      },
      { label: 'Urgent', count: s.priorityUrgent, color: '#f59e0b', pct: pct(s.priorityUrgent) },
      { label: 'STAT', count: s.priorityStat, color: '#dc2626', pct: pct(s.priorityStat) },
    ];
  });

  // ── Throughput summary ────────────────────────────────────────
  throughputCards = computed(() => {
    const s = this.summary();
    if (!s) return [];

    const weekTat =
      s.avgTurnaroundMinutesThisWeek !== null && s.avgTurnaroundMinutesThisWeek !== undefined
        ? Math.round(s.avgTurnaroundMinutesThisWeek) + ' min'
        : 'N/A';

    return [
      { label: 'Completed This Week', value: s.completedThisWeek },
      { label: 'Cancelled Today', value: s.cancelledToday },
      { label: 'Orders This Month', value: s.ordersThisMonth },
      { label: 'Avg TAT (Week)', value: weekTat },
    ];
  });

  ngOnInit(): void {
    this.dashboardService.getLabOpsSummary().subscribe({
      next: (data) => {
        this.summary.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load Lab Operations dashboard.');
        this.loading.set(false);
      },
    });
  }
}
