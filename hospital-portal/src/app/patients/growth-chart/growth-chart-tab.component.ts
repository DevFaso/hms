import {
  ChangeDetectionStrategy,
  Component,
  Input,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  GrowthChartResponse,
  GrowthPoint,
  VitalSignService,
} from '../../services/vital-sign.service';

export type GrowthMetric = 'weight' | 'height' | 'headCircumference';

interface SeriesPoint {
  ageDays: number;
  value: number;
  source: string | null;
  recordedAt: string;
}

interface PlottedPoint {
  x: number;
  y: number;
  ageDays: number;
  value: number;
  delivery: boolean;
}

interface ChartModel {
  points: PlottedPoint[];
  polyline: string;
  xTicks: { x: number; label: string }[];
  yTicks: { y: number; label: string }[];
  monthsMode: boolean;
}

const DAYS_PER_MONTH = 30.4375;
const DAYS_PER_YEAR = 365.25;

/**
 * The patient's own anthropometric trajectory (weight / height / head
 * circumference against age), seeded with the delivery-record birth weight
 * when one is linked. Inline SVG, zero dependencies — the partograph /
 * KPI-sparkline house pattern.
 *
 * Deliberately NO percentile curves: WHO reference data must be imported
 * from a verified source and clinically signed off (the V120 drug-KB
 * precedent), not reproduced from memory.
 */
@Component({
  selector: 'app-growth-chart-tab',
  standalone: true,
  imports: [CommonModule, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './growth-chart-tab.component.html',
  styleUrl: './growth-chart-tab.component.scss',
})
export class GrowthChartTabComponent implements OnInit {
  @Input({ required: true }) patientId!: string;

  private readonly vitalService = inject(VitalSignService);
  private readonly translate = inject(TranslateService);

  readonly loading = signal(true);
  readonly error = signal('');
  readonly chart = signal<GrowthChartResponse | null>(null);
  readonly metric = signal<GrowthMetric>('weight');

  readonly width = 640;
  readonly height = 300;
  readonly padLeft = 44;
  readonly padRight = 16;
  readonly padTop = 16;
  readonly padBottom = 40;

  readonly allPoints = computed<GrowthPoint[]>(() => this.chart()?.points ?? []);

  readonly seriesPoints = computed<SeriesPoint[]>(() => {
    const key = this.metric();
    return this.allPoints()
      .map((p) => ({
        ageDays: p.ageDays,
        value: this.metricValue(p, key),
        source: p.source,
        recordedAt: p.recordedAt,
      }))
      .filter((p): p is SeriesPoint => p.value != null)
      .sort((a, b) => a.ageDays - b.ageDays);
  });

  readonly metricCounts = computed<Record<GrowthMetric, number>>(() => {
    const counts: Record<GrowthMetric, number> = { weight: 0, height: 0, headCircumference: 0 };
    for (const p of this.allPoints()) {
      if (p.weightKg != null) counts.weight++;
      if (p.heightCm != null) counts.height++;
      if (p.headCircumferenceCm != null) counts.headCircumference++;
    }
    return counts;
  });

  readonly chartModel = computed<ChartModel | null>(() => {
    const pts = this.seriesPoints();
    if (pts.length === 0) return null;

    const maxAge = Math.max(...pts.map((p) => p.ageDays));
    const monthsMode = maxAge <= 2 * DAYS_PER_YEAR + 1;
    const unitDays = monthsMode ? DAYS_PER_MONTH : DAYS_PER_YEAR;
    const totalUnits = Math.max(3, Math.ceil(maxAge / unitDays) + 1);
    const unitStep = Math.max(1, Math.ceil(totalUnits / 8));
    const xMaxDays = totalUnits * unitDays;

    const xTicks: { x: number; label: string }[] = [];
    for (let unit = 0; unit <= totalUnits; unit += unitStep) {
      xTicks.push({ x: this.xFor(unit * unitDays, xMaxDays), label: String(unit) });
    }

    const values = pts.map((p) => p.value);
    const min = Math.min(...values);
    const max = Math.max(...values);
    const range = max - min || Math.max(max * 0.2, 1);
    const yMin = Math.max(0, min - range * 0.15);
    const yMax = max + range * 0.15;

    const yTicks: { y: number; label: string }[] = [];
    for (let i = 0; i <= 4; i++) {
      const value = yMin + ((yMax - yMin) * i) / 4;
      yTicks.push({
        y: this.yFor(value, yMin, yMax),
        label: (Math.round(value * 10) / 10).toString(),
      });
    }

    const points: PlottedPoint[] = pts.map((p) => ({
      x: this.xFor(p.ageDays, xMaxDays),
      y: this.yFor(p.value, yMin, yMax),
      ageDays: p.ageDays,
      value: p.value,
      delivery: p.source === 'DELIVERY',
    }));

    return {
      points,
      polyline: points.map((p) => `${p.x},${p.y}`).join(' '),
      xTicks,
      yTicks,
      monthsMode,
    };
  });

  readonly hasBirthSeed = computed(() => this.allPoints().some((p) => p.source === 'DELIVERY'));

  // The whole sentence lives in the translation file so FR/ES own the word order.
  readonly ariaLabel = computed(() =>
    this.translate.instant('GROWTH.CHART_ARIA', {
      count: this.seriesPoints().length,
      metric: this.metricLabel(this.metric()),
    }),
  );

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set('');
    this.vitalService.getGrowthChart(this.patientId).subscribe({
      next: (chart) => {
        this.chart.set(chart);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? this.translate.instant('GROWTH.LOAD_FAILED'));
        this.loading.set(false);
      },
    });
  }

  setMetric(metric: GrowthMetric): void {
    this.metric.set(metric);
  }

  metricLabel(metric: GrowthMetric): string {
    switch (metric) {
      case 'height':
        return this.translate.instant('GROWTH.METRIC_HEIGHT');
      case 'headCircumference':
        return this.translate.instant('GROWTH.METRIC_HEAD_CIRCUMFERENCE');
      default:
        return this.translate.instant('GROWTH.METRIC_WEIGHT');
    }
  }

  metricUnit(metric: GrowthMetric): string {
    return metric === 'weight' ? 'kg' : 'cm';
  }

  formatAge(ageDays: number): string {
    if (ageDays < 61) {
      return this.translate.instant('GROWTH.AGE_DAYS', { days: ageDays });
    }
    if (ageDays < 2 * DAYS_PER_YEAR) {
      return this.translate.instant('GROWTH.AGE_MONTHS', {
        months: Math.round(ageDays / DAYS_PER_MONTH),
      });
    }
    const years = Math.floor(ageDays / DAYS_PER_YEAR);
    const months = Math.round((ageDays - years * DAYS_PER_YEAR) / DAYS_PER_MONTH);
    return this.translate.instant('GROWTH.AGE_YEARS_MONTHS', { years, months });
  }

  formatMeasure(value: number | null | undefined, unit: string): string {
    return value === null || value === undefined ? '—' : `${value} ${unit}`;
  }

  sourceLabel(source: string | null): string {
    return source === 'DELIVERY'
      ? this.translate.instant('GROWTH.SOURCE_DELIVERY')
      : (source ?? '—');
  }

  private metricValue(p: GrowthPoint, metric: GrowthMetric): number | null {
    switch (metric) {
      case 'height':
        return p.heightCm;
      case 'headCircumference':
        return p.headCircumferenceCm;
      default:
        return p.weightKg;
    }
  }

  private xFor(ageDays: number, xMaxDays: number): number {
    const plotWidth = this.width - this.padLeft - this.padRight;
    return Math.round((this.padLeft + (ageDays / xMaxDays) * plotWidth) * 10) / 10;
  }

  private yFor(value: number, yMin: number, yMax: number): number {
    const plotHeight = this.height - this.padTop - this.padBottom;
    const span = yMax - yMin || 1;
    return Math.round((this.padTop + (1 - (value - yMin) / span) * plotHeight) * 10) / 10;
  }
}
