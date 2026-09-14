import {
  Component,
  computed,
  inject,
  OnDestroy,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';

import { RouterModule } from '@angular/router';
import { Subscription } from 'rxjs';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
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
  imports: [RouterModule, TranslateModule],
  templateUrl: './lab-ops-dashboard.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './lab-ops-dashboard.scss',
})
export class LabOpsDashboardComponent implements OnInit, OnDestroy {
  private readonly dashboardService = inject(DashboardService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /**
   * Bumps on every language change. Read inside each `computed()` so the
   * localized card and bar captions follow a runtime language switch instead
   * of freezing at whatever locale was active when the summary arrived.
   */
  private readonly langTick = signal(0);
  private langSub?: Subscription;

  loading = signal(true);
  summary = signal<LabOpsSummary | null>(null);

  // ── KPI stat cards ────────────────────────────────────────────
  statCards = computed<StatCard[]>(() => {
    this.langTick();
    const s = this.summary();
    if (!s) return [];

    const avgTat =
      s.avgTurnaroundMinutesToday !== null && s.avgTurnaroundMinutesToday !== undefined
        ? Math.round(s.avgTurnaroundMinutesToday) + ' min'
        : this.translate.instant('LAB_OPS.NOT_AVAILABLE');

    return [
      {
        key: 'orders_today',
        label: this.translate.instant('LAB_OPS.ORDERS_TODAY'),
        value: s.ordersToday,
        icon: 'science',
        color: '#0e7c6b',
        bgColor: '#ccebe4',
      },
      {
        key: 'completed_today',
        label: this.translate.instant('LAB_OPS.COMPLETED_TODAY'),
        value: s.completedToday,
        icon: 'check_circle',
        color: '#059669',
        bgColor: '#d1fae5',
      },
      {
        key: 'avg_tat',
        label: this.translate.instant('LAB_OPS.AVG_TAT_TODAY'),
        value: avgTat,
        icon: 'timer',
        color: '#d97706',
        bgColor: '#fef3c7',
      },
      {
        key: 'in_progress',
        label: this.translate.instant('LAB_OPS.IN_PROGRESS'),
        value: s.statusInProgress,
        icon: 'pending_actions',
        color: '#7c3aed',
        bgColor: '#ede9fe',
      },
      {
        key: 'orders_week',
        label: this.translate.instant('LAB_OPS.ORDERS_THIS_WEEK'),
        value: s.ordersThisWeek,
        icon: 'date_range',
        color: '#0891b2',
        bgColor: '#cffafe',
      },
      {
        key: 'aging',
        label: this.translate.instant('LAB_OPS.AGING'),
        value: s.ordersOlderThan24h,
        icon: 'warning',
        color: s.ordersOlderThan24h > 0 ? '#dc2626' : '#64748b',
        bgColor: s.ordersOlderThan24h > 0 ? '#fee2e2' : '#f1f5f9',
      },
    ];
  });

  // ── Status breakdown rows ─────────────────────────────────────
  statusRows = computed<StatusRow[]>(() => {
    this.langTick();
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

    // Status captions reuse the shared lab-order-status enum group so the
    // pipeline bars read the same as every other lab status badge.
    const statusLabel = (status: string): string =>
      this.translate.instant(`PORTAL.ENUM.LAB_ORDER_STATUS.${status}`);

    return [
      {
        label: statusLabel('ORDERED'),
        count: s.statusOrdered,
        color: '#6366f1',
        pct: pct(s.statusOrdered),
      },
      {
        label: statusLabel('PENDING'),
        count: s.statusPending,
        color: '#f59e0b',
        pct: pct(s.statusPending),
      },
      {
        label: statusLabel('COLLECTED'),
        count: s.statusCollected,
        color: '#06b6d4',
        pct: pct(s.statusCollected),
      },
      {
        label: statusLabel('RECEIVED'),
        count: s.statusReceived,
        color: '#8b5cf6',
        pct: pct(s.statusReceived),
      },
      {
        label: statusLabel('IN_PROGRESS'),
        count: s.statusInProgress,
        color: '#23b79c',
        pct: pct(s.statusInProgress),
      },
      {
        label: statusLabel('RESULTED'),
        count: s.statusResulted,
        color: '#10b981',
        pct: pct(s.statusResulted),
      },
      {
        label: statusLabel('VERIFIED'),
        count: s.statusVerified,
        color: '#059669',
        pct: pct(s.statusVerified),
      },
    ];
  });

  // ── Priority breakdown ────────────────────────────────────────
  priorityRows = computed(() => {
    this.langTick();
    const s = this.summary();
    if (!s) return [];
    const total = s.priorityRoutine + s.priorityUrgent + s.priorityStat;
    const pct = (v: number) => (total > 0 ? Math.round((v / total) * 100) : 0);
    // Priority captions reuse the shared urgency enum group (ROUTINE / URGENT /
    // STAT) rather than re-keying the same three words under LAB_OPS.
    const priorityLabel = (priority: string): string =>
      this.translate.instant(`PORTAL.ENUM.CONSULTATION_URGENCY.${priority}`);

    return [
      {
        label: priorityLabel('ROUTINE'),
        count: s.priorityRoutine,
        color: '#64748b',
        pct: pct(s.priorityRoutine),
      },
      {
        label: priorityLabel('URGENT'),
        count: s.priorityUrgent,
        color: '#f59e0b',
        pct: pct(s.priorityUrgent),
      },
      {
        label: priorityLabel('STAT'),
        count: s.priorityStat,
        color: '#dc2626',
        pct: pct(s.priorityStat),
      },
    ];
  });

  // ── Throughput summary ────────────────────────────────────────
  throughputCards = computed(() => {
    this.langTick();
    const s = this.summary();
    if (!s) return [];

    const weekTat =
      s.avgTurnaroundMinutesThisWeek !== null && s.avgTurnaroundMinutesThisWeek !== undefined
        ? Math.round(s.avgTurnaroundMinutesThisWeek) + ' min'
        : this.translate.instant('LAB_OPS.NOT_AVAILABLE');

    return [
      {
        label: this.translate.instant('LAB_OPS.COMPLETED_THIS_WEEK'),
        value: s.completedThisWeek,
      },
      { label: this.translate.instant('LAB_OPS.CANCELLED_TODAY'), value: s.cancelledToday },
      { label: this.translate.instant('LAB_OPS.ORDERS_THIS_MONTH'), value: s.ordersThisMonth },
      { label: this.translate.instant('LAB_OPS.AVG_TAT_WEEK'), value: weekTat },
    ];
  });

  ngOnInit(): void {
    this.langSub = this.translate.onLangChange.subscribe(() => {
      this.langTick.update((v) => v + 1);
    });
    this.dashboardService.getLabOpsSummary().subscribe({
      next: (data) => {
        this.summary.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('LAB_OPS.LOAD_FAILED'));
        this.loading.set(false);
      },
    });
  }

  ngOnDestroy(): void {
    this.langSub?.unsubscribe();
  }
}
