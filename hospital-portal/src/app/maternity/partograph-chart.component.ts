import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { PartographEntryResponse } from '../services/labor.service';

interface ChartPoint {
  x: number;
  y: number;
  hours: number;
  value: number;
}

/**
 * WHO partograph cervicogram: cervical dilation (● solid line) and head
 * descent in fifths (○ dashed line) plotted against hours since the
 * active-phase anchor, with the WHO alert line (1 cm/h from 4 cm) and the
 * action line (4 h to its right). Inline SVG, zero dependencies — same
 * approach as the analytics KPI sparkline.
 */
@Component({
  selector: 'app-partograph-chart',
  standalone: true,
  imports: [TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <figure class="partograph-figure">
      <svg
        [attr.viewBox]="'0 0 ' + width + ' ' + height"
        preserveAspectRatio="xMidYMid meet"
        role="img"
        focusable="false"
        [attr.aria-label]="ariaLabel()"
      >
        <!-- grid -->
        @for (line of hourGrid; track line.hour) {
          <line
            [attr.x1]="line.x"
            [attr.y1]="padTop"
            [attr.x2]="line.x"
            [attr.y2]="height - padBottom"
            stroke="#e5e7eb"
            stroke-width="1"
          />
          <text
            [attr.x]="line.x"
            [attr.y]="height - padBottom + 14"
            text-anchor="middle"
            class="axis-text"
          >
            {{ line.hour }}
          </text>
        }
        @for (line of cmGrid; track line.cm) {
          <line
            [attr.x1]="padLeft"
            [attr.y1]="line.y"
            [attr.x2]="width - padRight"
            [attr.y2]="line.y"
            stroke="#e5e7eb"
            stroke-width="1"
          />
          <text [attr.x]="padLeft - 6" [attr.y]="line.y + 3" text-anchor="end" class="axis-text">
            {{ line.cm }}
          </text>
        }

        <!-- WHO alert + action lines -->
        <line
          [attr.x1]="alertLine.x1"
          [attr.y1]="alertLine.y1"
          [attr.x2]="alertLine.x2"
          [attr.y2]="alertLine.y2"
          stroke="#d97706"
          stroke-width="1.5"
          stroke-dasharray="6 3"
        />
        <line
          [attr.x1]="actionLine.x1"
          [attr.y1]="actionLine.y1"
          [attr.x2]="actionLine.x2"
          [attr.y2]="actionLine.y2"
          stroke="#dc2626"
          stroke-width="1.5"
          stroke-dasharray="6 3"
        />
        <text
          [attr.x]="alertLine.x2 - 4"
          [attr.y]="alertLine.y2 - 4"
          text-anchor="end"
          class="line-label alert-label"
        >
          {{ 'LABOR.ALERT_LINE' | translate }}
        </text>
        <text
          [attr.x]="actionLine.x2 - 4"
          [attr.y]="actionLine.y2 + 12"
          text-anchor="end"
          class="line-label action-label"
        >
          {{ 'LABOR.ACTION_LINE' | translate }}
        </text>

        <!-- descent (dashed, open circles) -->
        @if (descentPointsAttr().length > 0) {
          <polyline
            [attr.points]="descentPolyline()"
            fill="none"
            stroke="#6366f1"
            stroke-width="1.5"
            stroke-dasharray="3 3"
            stroke-linejoin="round"
          />
          @for (p of descentPointsAttr(); track $index) {
            <circle
              [attr.cx]="p.x"
              [attr.cy]="p.y"
              r="3.5"
              fill="#fff"
              stroke="#6366f1"
              stroke-width="1.5"
            />
          }
        }

        <!-- dilation (solid, filled circles) -->
        @if (dilationPointsAttr().length > 0) {
          <polyline
            [attr.points]="dilationPolyline()"
            fill="none"
            stroke="#0f766e"
            stroke-width="2"
            stroke-linejoin="round"
            stroke-linecap="round"
          />
          @for (p of dilationPointsAttr(); track $index) {
            <circle [attr.cx]="p.x" [attr.cy]="p.y" r="3.5" fill="#0f766e" />
          }
        }

        <!-- axis titles -->
        <text [attr.x]="width / 2" [attr.y]="height - 4" text-anchor="middle" class="axis-title">
          {{ 'LABOR.CHART_X_AXIS' | translate }}
        </text>
      </svg>
      <figcaption class="partograph-legend">
        <span class="legend-item"
          ><span class="swatch swatch-dilation"></span>{{ 'LABOR.DILATION' | translate }}</span
        >
        <span class="legend-item"
          ><span class="swatch swatch-descent"></span>{{ 'LABOR.DESCENT' | translate }}</span
        >
        <span class="legend-item"
          ><span class="swatch swatch-alert"></span>{{ 'LABOR.ALERT_LINE' | translate }}</span
        >
        <span class="legend-item"
          ><span class="swatch swatch-action"></span>{{ 'LABOR.ACTION_LINE' | translate }}</span
        >
      </figcaption>
    </figure>
  `,
  styles: [
    `
      .partograph-figure {
        margin: 0;
      }
      svg {
        width: 100%;
        height: auto;
        display: block;
        background: #fff;
        border: 1px solid #e5e7eb;
        border-radius: 8px;
      }
      .axis-text {
        font-size: 9px;
        fill: #6b7280;
      }
      .axis-title {
        font-size: 9px;
        fill: #6b7280;
        text-transform: uppercase;
        letter-spacing: 0.05em;
      }
      .line-label {
        font-size: 8px;
      }
      .alert-label {
        fill: #d97706;
      }
      .action-label {
        fill: #dc2626;
      }
      .partograph-legend {
        display: flex;
        flex-wrap: wrap;
        gap: 0.5rem 1rem;
        margin-top: 0.4rem;
        font-size: 0.75rem;
        color: #6b7280;
      }
      .legend-item {
        display: inline-flex;
        align-items: center;
        gap: 0.3rem;
      }
      .swatch {
        display: inline-block;
        width: 14px;
        height: 3px;
        border-radius: 2px;
      }
      .swatch-dilation {
        background: #0f766e;
      }
      .swatch-descent {
        background: #6366f1;
      }
      .swatch-alert {
        background: #d97706;
      }
      .swatch-action {
        background: #dc2626;
      }
    `,
  ],
})
export class PartographChartComponent {
  private readonly translate = inject(TranslateService);

  /** Entries of the episode; only those with an active-phase offset are plotted. */
  entries = input.required<PartographEntryResponse[]>();

  readonly width = 480;
  readonly height = 240;
  readonly padLeft = 28;
  readonly padRight = 12;
  readonly padTop = 12;
  readonly padBottom = 32;

  /** X axis spans 0–12 h of active phase; Y axis 0–10 cm (descent shares 0–5). */
  readonly maxHours = 12;
  readonly maxCm = 10;

  readonly hourGrid = Array.from({ length: this.maxHours + 1 }, (_, hour) => ({
    hour,
    x: this.xFor(hour),
  }));
  readonly cmGrid = Array.from({ length: this.maxCm + 1 }, (_, cm) => ({
    cm,
    y: this.yFor(cm),
  }));

  /** Alert line: (0 h, 4 cm) → (6 h, 10 cm). */
  readonly alertLine = {
    x1: this.xFor(0),
    y1: this.yFor(4),
    x2: this.xFor(6),
    y2: this.yFor(10),
  };
  /** Action line: 4 h to the right of the alert line. */
  readonly actionLine = {
    x1: this.xFor(4),
    y1: this.yFor(4),
    x2: this.xFor(10),
    y2: this.yFor(10),
  };

  readonly dilationPointsAttr = computed<ChartPoint[]>(() =>
    this.pointsFor((entry) => entry.cervicalDilationCm),
  );
  readonly descentPointsAttr = computed<ChartPoint[]>(() =>
    this.pointsFor((entry) => entry.descentFifths),
  );

  readonly dilationPolyline = computed(() => this.toPolyline(this.dilationPointsAttr()));
  readonly descentPolyline = computed(() => this.toPolyline(this.descentPointsAttr()));

  // The whole sentence lives in the translation file so FR/ES own the word
  // order (house rule from the KPI sparkline — see its doc-comment).
  readonly ariaLabel = computed(() =>
    this.translate.instant('LABOR.PARTOGRAPH_ARIA', {
      count: this.dilationPointsAttr().length,
    }),
  );

  private pointsFor(value: (entry: PartographEntryResponse) => number | null): ChartPoint[] {
    return this.entries()
      .filter(
        (entry) =>
          entry.hoursSinceActivePhaseStart != null &&
          entry.hoursSinceActivePhaseStart <= this.maxHours &&
          value(entry) != null,
      )
      .map((entry) => ({
        hours: entry.hoursSinceActivePhaseStart as number,
        value: value(entry) as number,
        x: this.xFor(entry.hoursSinceActivePhaseStart as number),
        y: this.yFor(value(entry) as number),
      }))
      .sort((a, b) => a.hours - b.hours);
  }

  private toPolyline(points: ChartPoint[]): string {
    return points.map((p) => `${p.x},${p.y}`).join(' ');
  }

  private xFor(hours: number): number {
    const plotWidth = this.width - this.padLeft - this.padRight;
    return this.padLeft + (hours / this.maxHours) * plotWidth;
  }

  private yFor(cm: number): number {
    const plotHeight = this.height - this.padTop - this.padBottom;
    return this.padTop + (1 - cm / this.maxCm) * plotHeight;
  }
}
