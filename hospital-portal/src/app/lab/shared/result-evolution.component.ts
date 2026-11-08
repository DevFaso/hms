import { formatDecimal } from '../../shared/i18n/app-locale';
import { Component, computed, input, linkedSignal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import { LabResultTrendPoint } from '../../services/lab.service';
import { DateTimePipe } from '../../shared/pipes/date-time.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { LabValuePipe } from './lab-value.pipe';
import { LabCorrectionBadgeComponent } from './lab-correction-badge.component';
import { evolutionSeries, observationDelta } from './result-evolution';
import { serverInstant } from '../../shared/date-utils';

@Component({
  selector: 'app-result-evolution',
  imports: [
    FormsModule,
    DatePipe,
    TranslateModule,
    DateTimePipe,
    ServerTimePipe,
    LabValuePipe,
    LabCorrectionBadgeComponent,
  ],
  template: `
    <section class="evolution" aria-labelledby="result-evolution-title">
      <div class="heading">
        <h3 id="result-evolution-title">
          {{ (hasCorrections() ? 'LAB_EVOLUTION.AUDIT_TITLE' : 'LAB_EVOLUTION.TITLE') | translate }}
        </h3>
        @if (units().length > 1) {
          <label
            >{{ 'LAB_EVOLUTION.UNIT' | translate }}
            <select [ngModel]="unit()" (ngModelChange)="chosenUnit.set($event)">
              @for (u of units(); track u) {
                <option [value]="u">{{ u }}</option>
              }
            </select>
          </label>
        }
      </div>
      @if (loading()) {
        <p role="status">{{ 'LAB_EVOLUTION.LOADING' | translate }}</p>
      } @else if (failed()) {
        <p role="alert">{{ 'LAB_EVOLUTION.ERROR' | translate }}</p>
      } @else {
        <p class="hint">
          {{
            (hasCorrections() ? 'LAB_EVOLUTION.CORRECTION_NOTE' : 'LAB_EVOLUTION.NOTE') | translate
          }}
        </p>
        @if (points().length > 1) {
          <figure>
            <figcaption>{{ unit() }} · {{ 'LAB_EVOLUTION.TIME_AXIS' | translate }}</figcaption>
            <svg
              viewBox="0 0 600 240"
              role="img"
              [attr.aria-label]="'LAB_EVOLUTION.CHART_LABEL' | translate"
            >
              <line x1="55" y1="20" x2="55" y2="205" />
              <line x1="55" y1="205" x2="580" y2="205" />
              <text x="48" y="28" text-anchor="end">{{ formatAxis(bounds().max) }}</text>
              <text x="48" y="205" text-anchor="end">{{ formatAxis(bounds().min) }}</text>
              @if (!hasCorrections()) {
                <polyline [attr.points]="linePoints()" />
              }
              @for (p of points(); track $index) {
                <circle
                  [attr.cx]="x(p.time)"
                  [attr.cy]="y(p.value)"
                  r="5"
                  [class.corrected]="p.corrected"
                >
                  <title>
                    {{ p.resultDate | serverTime | dateTime }} · {{ p.value | labValue: unit()
                    }}{{ p.corrected ? ' · ' + ('LAB_RESULTS.CORRECTED' | translate) : '' }}
                  </title>
                </circle>
              }
              <text x="55" y="232">
                {{ points()[0].resultDate | serverTime | date: 'shortDate' }}
              </text>
              <text x="580" y="232" text-anchor="end">
                {{ points()[points().length - 1].resultDate | serverTime | date: 'shortDate' }}
              </text>
            </svg>
          </figure>
        } @else {
          <p class="empty">{{ 'LAB_EVOLUTION.INSUFFICIENT' | translate }}</p>
        }
        @if (history().length) {
          <details>
            <summary>
              {{ 'LAB_EVOLUTION.OBSERVATIONS' | translate }} ({{ history().length }})
            </summary>
            <table>
              <thead>
                <tr>
                  <th scope="col">{{ 'LAB_RESULTS.RESULT_DATE' | translate }}</th>
                  <th scope="col">{{ 'LAB_RESULTS.VALUE' | translate }}</th>
                  <th scope="col">{{ 'LAB_RESULTS.DELTA' | translate }}</th>
                </tr>
              </thead>
              <tbody>
                @for (p of observations(); track $index) {
                  <tr>
                    <td>{{ p.resultDate | serverTime | dateTime }}</td>
                    <td>
                      {{ p.resultValue | labValue: p.resultUnit }}
                      <app-lab-correction-badge [corrected]="p.corrected" />
                    </td>
                    <td>{{ deltaAt($index) }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </details>
        }
      }
    </section>
  `,
  styles: `
    :host {
      display: block;
      margin: 20px 0;
    }
    .evolution {
      padding: 18px;
      border: 1px solid #cbdedc;
      border-radius: 12px;
      background: #f8fcfb;
      color: #193c46;
    }
    .heading {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      gap: 12px;
      align-items: center;
    }
    h3 {
      margin: 0;
      font-size: 16px;
    }
    label,
    .hint,
    figcaption,
    summary {
      font-size: 12px;
    }
    select {
      padding: 6px;
      margin-left: 6px;
      border: 1px solid #9bb9b4;
      border-radius: 6px;
      background: white;
    }
    .hint {
      line-height: 1.6;
    }
    figure {
      margin: 16px 0;
    }
    svg {
      display: block;
      width: 100%;
      overflow: visible;
    }
    line {
      stroke: #a4b7bb;
    }
    polyline {
      fill: none;
      stroke: #08796e;
      stroke-width: 2;
    }
    circle {
      fill: #08796e;
      stroke: white;
      stroke-width: 2;
    }
    circle.corrected {
      fill: white;
      stroke: #8b4a0d;
      stroke-width: 3;
    }
    text {
      fill: #405a65;
      font-size: 14px;
    }
    summary {
      cursor: pointer;
      padding: 8px 0;
      font-weight: 600;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      font-size: 12px;
    }
    td,
    th {
      text-align: left;
      padding: 8px 4px;
      border-bottom: 1px solid #dbe6e5;
    }
    .empty {
      padding: 12px;
      background: white;
      border-radius: 8px;
      font-size: 13px;
    }
  `,
})
export class ResultEvolutionComponent {
  history = input<LabResultTrendPoint[]>([]);
  loading = input(false);
  failed = input(false);
  resultId = input('');
  chosenUnit = linkedSignal({
    source: () => ({ id: this.resultId(), history: this.history(), unit: this.preferredUnit() }),
    computation: () => '',
  });
  preferredUnit = input('');
  units = computed(() => [
    ...new Set(
      this.history()
        .map((p) => p.resultUnit?.trim())
        .filter(Boolean),
    ),
  ]);
  unit = computed(() =>
    this.units().includes(this.chosenUnit())
      ? this.chosenUnit()
      : this.units().includes(this.preferredUnit().trim())
        ? this.preferredUnit().trim()
        : (this.units()[0] ?? ''),
  );
  points = computed(() => evolutionSeries(this.history(), this.unit()));
  hasCorrections = computed(() => this.history().some((point) => point.corrected));
  observations = computed(() =>
    [...this.history()].sort((a, b) => {
      const left = serverInstant(a.resultDate).getTime();
      const right = serverInstant(b.resultDate).getTime();
      return (
        (Number.isFinite(right) ? right : -Infinity) - (Number.isFinite(left) ? left : -Infinity)
      );
    }),
  );
  deltaAt(index: number): string {
    if (this.hasCorrections()) return '—';
    const current = this.observations()[index];
    const delta = observationDelta(current, this.observations()[index + 1]);
    if (delta === null) return '—';
    const previous = this.observations()[index + 1];
    const precision = Math.min(
      20,
      Math.max(
        ...[current, previous].map((p) => (p.resultValue?.trim().split(/[.,]/)[1] ?? '').length),
      ),
    );
    return `${delta > 0 ? '+' : ''}${formatDecimal(delta, precision)} ${current.resultUnit.trim()}`;
  }
  linePoints = computed(() =>
    this.points()
      .map((p) => `${this.x(p.time)},${this.y(p.value)}`)
      .join(' '),
  );
  bounds = computed(() => {
    const values = this.points().map((p) => p.value);
    const min = Math.min(...values),
      max = Math.max(...values);
    const pad = Math.max((max - min) * 0.1, Math.abs(max) * 0.01, 0.01);
    return { min: min - pad, max: max + pad };
  });
  formatAxis(value: number): string {
    return formatDecimal(value, 3);
  }
  x(time: number): number {
    const times = this.points().map((p) => p.time);
    const min = Math.min(...times),
      span = Math.max(...times) - min;
    return span ? 65 + ((time - min) / span) * 505 : 320;
  }
  y(value: number): number {
    const { min, max } = this.bounds();
    return 200 - ((value - min) / (max - min)) * 175;
  }
}
