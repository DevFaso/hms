import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';

import { DashboardService, KpiDashboard } from '../../services/dashboard.service';

interface KpiCard {
  /** Translation key prefix; the template appends ".label" and ".help". */
  i18nKey: string;
  /** Formatted main value (or em-dash if no sample). */
  display: string;
  /** Secondary line — "n=<sample>" or "<noShow>/<total>". */
  sub: string;
  /** Material-icons name. */
  icon: string;
  /** Card accent color. */
  color: string;
  /** Card pale background. */
  bg: string;
}

/**
 * Three-card KPI rollup component for the row-32 dashboard.
 *
 * <p>Foundation-pass scope: renders door-to-doctor (avg minutes),
 * dispense lead time (avg minutes), and no-show rate (percent) for
 * the last 30 days against the current hospital context. Charting +
 * trend-over-time visualizations are the row-32 follow-on.
 *
 * <p>Keyboard contract (per docs/ui/accessibility.md): cards are
 * focusable in source order; the screen-reader name comes from the
 * translation key, the value is announced as the secondary text.
 */
@Component({
  selector: 'app-kpi-cards',
  standalone: true,
  imports: [CommonModule, TranslateModule],
  templateUrl: './kpi-cards.component.html',
  styleUrl: './kpi-cards.component.scss',
})
export class KpiCardsComponent implements OnInit {
  private readonly dashboardService = inject(DashboardService);

  /** Default lookback window: 30 days ending today (inclusive). */
  private static readonly DEFAULT_WINDOW_DAYS = 30;

  loading = signal(true);
  dashboard = signal<KpiDashboard | null>(null);

  cards = computed<KpiCard[]>(() => {
    const d = this.dashboard();
    if (!d) return [];

    return [
      {
        i18nKey: 'ANALYTICS.KPI.DOOR_TO_DOCTOR',
        display: this.formatMinutes(d.doorToDoctor?.averageMinutes),
        sub: this.formatSampleSize(d.doorToDoctor?.sampleSize),
        icon: 'timer',
        color: '#3b82f6',
        bg: '#eff6ff',
      },
      {
        i18nKey: 'ANALYTICS.KPI.DISPENSE_LEAD_TIME',
        display: this.formatMinutes(d.dispenseLeadTime?.averageMinutes),
        sub: this.formatSampleSize(d.dispenseLeadTime?.sampleSize),
        icon: 'local_pharmacy',
        color: '#8b5cf6',
        bg: '#f5f3ff',
      },
      {
        i18nKey: 'ANALYTICS.KPI.NO_SHOW_RATE',
        display: this.formatPercent(d.noShowRate?.rate),
        sub: this.formatNoShowSub(d.noShowRate),
        icon: 'event_busy',
        color: '#ef4444',
        bg: '#fef2f2',
      },
    ];
  });

  ngOnInit(): void {
    const today = new Date();
    const start = new Date(today);
    start.setDate(start.getDate() - (KpiCardsComponent.DEFAULT_WINDOW_DAYS - 1));

    const from = this.toIsoDate(start);
    const to = this.toIsoDate(today);

    this.dashboardService.getKpiDashboard(from, to).subscribe({
      next: (d) => {
        this.dashboard.set(d ?? null);
        this.loading.set(false);
      },
      error: () => {
        this.dashboard.set(null);
        this.loading.set(false);
      },
    });
  }

  private toIsoDate(d: Date): string {
    return d.toISOString().slice(0, 10);
  }

  private formatMinutes(value: number | undefined): string {
    if (value == null || !Number.isFinite(value)) return '—';
    return `${Math.round(value)} min`;
  }

  private formatPercent(value: number | undefined): string {
    if (value == null || !Number.isFinite(value)) return '—';
    return `${(value * 100).toFixed(1)}%`;
  }

  private formatSampleSize(n: number | undefined): string {
    const v = n ?? 0;
    return v === 0 ? 'no data in window' : `n=${v}`;
  }

  private formatNoShowSub(no: KpiDashboard['noShowRate'] | undefined): string {
    if (!no || no.totalAppointments === 0) return 'no appointments in window';
    return `${no.noShowCount}/${no.totalAppointments}`;
  }
}
