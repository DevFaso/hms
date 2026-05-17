import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Inline-SVG sparkline for one KPI card (roadmap row 32 follow-on).
 *
 * <p>Renders a polyline over a fixed-aspect SVG viewBox plus an area
 * fill. Designed to be tiny — no chart library, no axes, no labels.
 * The card supplies a `label` for the accessible name; the sparkline
 * itself is `role="img"` and announces "<label> trend, N points".
 *
 * <p>Gaps in the input series (`null` / `undefined` values) split the
 * polyline so a missing day draws nothing rather than collapsing the
 * line to zero. Single-point series collapse to a dot.
 */
@Component({
  selector: 'app-kpi-sparkline',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      class="kpi-sparkline"
      [attr.viewBox]="viewBox"
      preserveAspectRatio="none"
      role="img"
      [attr.aria-label]="ariaLabel()"
      focusable="false"
    >
      @for (segment of segments(); track $index) {
        <polyline
          class="kpi-sparkline__line"
          [attr.points]="segment"
          [attr.stroke]="color()"
          fill="none"
          stroke-width="1.5"
          stroke-linejoin="round"
          stroke-linecap="round"
        />
      }
      @if (dots().length > 0) {
        @for (dot of dots(); track $index) {
          <circle
            class="kpi-sparkline__dot"
            [attr.cx]="dot.x"
            [attr.cy]="dot.y"
            [attr.fill]="color()"
            r="2"
          />
        }
      }
    </svg>
  `,
  styles: [`
    .kpi-sparkline {
      width: 100%;
      height: 32px;
      display: block;
    }
  `],
})
export class KpiSparklineComponent {
  /** Series — `null`/`undefined` values mark gaps. */
  series = input.required<Array<number | null | undefined>>();
  /** Stroke color (defaults to the card accent). */
  color = input<string>('#3b82f6');
  /** Accessible label prefix (e.g. "Door-to-doctor trend"). */
  label = input<string>('trend');

  /** Fixed viewBox so the sparkline scales to the card width. */
  viewBox = '0 0 100 32';

  private readonly width = 100;
  private readonly height = 32;
  private readonly padY = 4;

  /**
   * Polyline segments (one per contiguous run of finite values).
   * Gaps in the series produce separate segments rather than a line
   * that dips through the missing data.
   */
  segments = computed<string[]>(() => {
    const data = this.series();
    if (!data || data.length === 0) return [];
    const points = this.dataPoints(data);
    if (points.length === 0) return [];

    const out: string[] = [];
    let current: string[] = [];
    let lastIdx = -2;
    for (const p of points) {
      if (lastIdx >= 0 && p.idx !== lastIdx + 1) {
        if (current.length >= 2) out.push(current.join(' '));
        current = [];
      }
      current.push(`${p.x.toFixed(2)},${p.y.toFixed(2)}`);
      lastIdx = p.idx;
    }
    if (current.length >= 2) out.push(current.join(' '));
    return out;
  });

  /** Single-point runs render as dots so they're visible at all. */
  dots = computed<Array<{ x: number; y: number }>>(() => {
    const data = this.series();
    if (!data || data.length === 0) return [];
    const points = this.dataPoints(data);
    const out: Array<{ x: number; y: number }> = [];
    let runStart = -1;
    let runLen = 0;
    let lastIdx = -2;
    for (let i = 0; i < points.length; i++) {
      const p = points[i];
      if (p.idx === lastIdx + 1) {
        runLen++;
      } else {
        if (runLen === 1) {
          const sp = points[runStart];
          out.push({ x: sp.x, y: sp.y });
        }
        runStart = i;
        runLen = 1;
      }
      lastIdx = p.idx;
    }
    if (runLen === 1 && runStart >= 0) {
      const sp = points[runStart];
      out.push({ x: sp.x, y: sp.y });
    }
    return out;
  });

  ariaLabel = computed<string>(() => {
    const finite = this.series().filter(
      (v): v is number => v != null && Number.isFinite(v),
    );
    return `${this.label()} trend, ${finite.length} data points`;
  });

  /**
   * Map `(idx, value)` pairs to SVG coordinates. Values outside the
   * finite range are dropped. The y-axis is normalised against the
   * min/max so the sparkline shows shape, not absolute magnitude.
   */
  private dataPoints(data: Array<number | null | undefined>): Array<{ idx: number; x: number; y: number }> {
    const finite: Array<{ idx: number; value: number }> = [];
    data.forEach((v, idx) => {
      if (v != null && Number.isFinite(v)) {
        finite.push({ idx, value: v });
      }
    });
    if (finite.length === 0) return [];
    const values = finite.map((f) => f.value);
    const min = Math.min(...values);
    const max = Math.max(...values);
    const range = max - min || 1; // avoid div-by-zero on flat lines
    const n = Math.max(data.length - 1, 1);

    return finite.map(({ idx, value }) => {
      const x = (idx / n) * this.width;
      // Invert y so larger values sit higher on the chart.
      const normalised = (value - min) / range;
      const y = this.padY + (1 - normalised) * (this.height - 2 * this.padY);
      return { idx, x, y };
    });
  }
}
