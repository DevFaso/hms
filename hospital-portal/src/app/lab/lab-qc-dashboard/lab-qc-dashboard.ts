import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { forkJoin } from 'rxjs';
import {
  LabService,
  LabQcSummary,
  LabQcEvent,
  LabValidationSummary,
} from '../../services/lab.service';
import { ToastService } from '../../core/toast.service';

interface QcPoint {
  x: number;
  y: number;
  zone: 'ok' | 'warn' | 'fail';
  title: string;
}

interface QcZoneRect {
  y: number;
  height: number;
  fill: string;
}

interface QcChartGroup {
  level: string;
  mean: number;
  sd: number;
  points: QcPoint[];
  polyline: string;
  meanY: number;
  p1sY: number;
  m1sY: number;
  p2sY: number;
  m2sY: number;
  p3sY: number;
  m3sY: number;
  zoneRects: QcZoneRect[];
  yAxisLabels: { y: number; label: string }[];
  xAxisLabels: { x: number; label: string }[];
  insufficient: boolean;
}

@Component({
  selector: 'app-lab-qc-dashboard',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './lab-qc-dashboard.html',
  styleUrl: './lab-qc-dashboard.scss',
})
export class LabQcDashboardComponent implements OnInit {
  private readonly labService = inject(LabService);
  private readonly toast = inject(ToastService);

  loading = signal(true);
  qcSummary = signal<LabQcSummary[]>([]);
  validationSummary = signal<LabValidationSummary[]>([]);

  // ── Drill-down state ──────────────────────────────────────
  expandedDefinitionId = signal<string | null>(null);
  loadingChart = signal(false);
  qcEvents = signal<LabQcEvent[]>([]);
  readonly qcChartGroups = computed(() => this.buildChartGroups(this.qcEvents()));

  // ── Computed aggregates ───────────────────────────────────
  totalQcEvents = computed(() => this.qcSummary().reduce((s, r) => s + r.totalEvents, 0));
  totalQcFailures = computed(() => this.qcSummary().reduce((s, r) => s + r.failedEvents, 0));
  totalValidationStudies = computed(() =>
    this.validationSummary().reduce((s, r) => s + r.totalStudies, 0),
  );
  overallPassRate = computed(() => {
    const total = this.totalValidationStudies();
    const passed = this.validationSummary().reduce((s, r) => s + r.passedStudies, 0);
    return total > 0 ? (passed / total) * 100 : 0;
  });

  ngOnInit(): void {
    forkJoin({
      qc: this.labService.getQcSummary(),
      validation: this.labService.getValidationSummary(),
    }).subscribe({
      next: ({ qc, validation }) => {
        this.qcSummary.set(qc);
        this.validationSummary.set(validation);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load QC dashboard data.');
        this.loading.set(false);
      },
    });
  }

  // ── Drill-down: toggle chart for a test definition ────────
  toggleChart(definitionId: string): void {
    if (this.expandedDefinitionId() === definitionId) {
      this.expandedDefinitionId.set(null);
      this.qcEvents.set([]);
      return;
    }
    this.expandedDefinitionId.set(definitionId);
    this.loadingChart.set(true);
    this.labService.getQcEventsByDefinition(definitionId).subscribe({
      next: (events) => {
        this.qcEvents.set(events);
        this.loadingChart.set(false);
      },
      error: () => {
        this.toast.error('Failed to load QC events for chart.');
        this.loadingChart.set(false);
      },
    });
  }

  // ── Levey-Jennings chart computation ──────────────────────
  private readonly ML = 58;
  private readonly MR = 12;
  private readonly MT = 15;
  private readonly MB = 42;
  private readonly CW = 520;
  private readonly CH = 200;
  private readonly PW = this.CW - this.ML - this.MR; // 450
  private readonly PH = this.CH - this.MT - this.MB; // 143
  private readonly YSPAN = 3.5;

  private buildChartGroups(events: LabQcEvent[]): QcChartGroup[] {
    const levels = ['LOW_CONTROL', 'HIGH_CONTROL'] as const;
    const groups: QcChartGroup[] = [];

    for (const level of levels) {
      const levelEvents = events
        .filter((e) => e.qcLevel === level)
        .sort((a, b) => new Date(a.recordedAt).getTime() - new Date(b.recordedAt).getTime());

      if (levelEvents.length === 0) continue;

      const emptyGroup: QcChartGroup = {
        level,
        mean: 0,
        sd: 0,
        points: [],
        polyline: '',
        meanY: 0,
        p1sY: 0,
        m1sY: 0,
        p2sY: 0,
        m2sY: 0,
        p3sY: 0,
        m3sY: 0,
        zoneRects: [],
        yAxisLabels: [],
        xAxisLabels: [],
        insufficient: true,
      };

      if (levelEvents.length < 3) {
        groups.push(emptyGroup);
        continue;
      }

      const vals = levelEvents.map((e) => e.measuredValue);
      const mean = vals.reduce((a, b) => a + b, 0) / vals.length;
      const variance = vals.reduce((a, v) => a + (v - mean) ** 2, 0) / vals.length;
      const sd = Math.sqrt(variance);

      if (sd === 0) {
        groups.push(emptyGroup);
        continue;
      }

      const n = levelEvents.length;
      const yRange = 2 * this.YSPAN * sd;
      const yBot = mean - this.YSPAN * sd;
      const yToSvg = (v: number) => this.MT + this.PH - ((v - yBot) / yRange) * this.PH;
      const xToSvg = (i: number) => this.ML + (n > 1 ? (i / (n - 1)) * this.PW : this.PW / 2);

      const meanY = yToSvg(mean);
      const p1sY = yToSvg(mean + sd);
      const m1sY = yToSvg(mean - sd);
      const p2sY = yToSvg(mean + 2 * sd);
      const m2sY = yToSvg(mean - 2 * sd);
      const p3sY = yToSvg(mean + 3 * sd);
      const m3sY = yToSvg(mean - 3 * sd);

      const bottomEdge = this.MT + this.PH;
      const zoneRects: QcZoneRect[] = [
        { y: this.MT, height: p3sY - this.MT, fill: '#fee2e2' },
        { y: p3sY, height: p2sY - p3sY, fill: '#fef3c7' },
        { y: p2sY, height: p1sY - p2sY, fill: '#fffbeb' },
        { y: p1sY, height: m1sY - p1sY, fill: '#f0fdf4' },
        { y: m1sY, height: m2sY - m1sY, fill: '#fffbeb' },
        { y: m2sY, height: m3sY - m2sY, fill: '#fef3c7' },
        { y: m3sY, height: bottomEdge - m3sY, fill: '#fee2e2' },
      ];

      const points: QcPoint[] = levelEvents.map((e, i) => {
        const v = e.measuredValue;
        const dev = Math.abs(v - mean) / sd;
        const zone = this.classifyZone(dev);
        const dateStr = new Date(e.recordedAt).toLocaleDateString('en-US', {
          month: 'short',
          day: 'numeric',
          year: 'numeric',
        });
        return { x: xToSvg(i), y: yToSvg(v), zone, title: `${dateStr} | ${v.toFixed(3)}` };
      });

      const polyline = points.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ');

      const prec = this.sdPrecision(sd);
      const f = (v: number) => v.toFixed(prec);
      const yAxisLabels = [
        { y: p3sY, label: `+3s (${f(mean + 3 * sd)})` },
        { y: p2sY, label: `+2s` },
        { y: p1sY, label: `+1s` },
        { y: meanY, label: `X\u0305 ${f(mean)}` },
        { y: m1sY, label: `-1s` },
        { y: m2sY, label: `-2s` },
        { y: m3sY, label: `-3s (${f(mean - 3 * sd)})` },
      ];

      const xAxisLabels: { x: number; label: string }[] = [];
      const fmtDate = (e: LabQcEvent) =>
        new Date(e.recordedAt).toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
      xAxisLabels.push({ x: xToSvg(0), label: fmtDate(levelEvents[0]) });
      if (n > 2) {
        const mid = Math.floor(n / 2);
        xAxisLabels.push({ x: xToSvg(mid), label: fmtDate(levelEvents[mid]) });
      }
      if (n > 1) {
        xAxisLabels.push({ x: xToSvg(n - 1), label: fmtDate(levelEvents[n - 1]) });
      }

      groups.push({
        level,
        mean,
        sd,
        points,
        polyline,
        meanY,
        p1sY,
        m1sY,
        p2sY,
        m2sY,
        p3sY,
        m3sY,
        zoneRects,
        yAxisLabels,
        xAxisLabels,
        insufficient: false,
      });
    }

    return groups;
  }

  private classifyZone(dev: number): 'ok' | 'warn' | 'fail' {
    if (dev > 2) return 'fail';
    if (dev > 1) return 'warn';
    return 'ok';
  }

  private sdPrecision(sd: number): number {
    if (sd < 1) return 3;
    if (sd < 10) return 2;
    return 1;
  }
}
