import { formatDecimal } from '../../shared/i18n/app-locale';
import { Component, computed, effect, inject, input, linkedSignal, signal } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import { LabResultReferenceRange, LabResultTrendPoint } from '../../services/lab.service';
import { DayMonthPipe } from '../../shared/pipes/day-month.pipe';
import { DateTimePipe } from '../../shared/pipes/date-time.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { LabValuePipe } from './lab-value.pipe';
import { LabCorrectionBadgeComponent } from './lab-correction-badge.component';
import {
  deltaPrecision,
  evolutionSeries,
  exactObservation,
  niceAxis,
  observationDelta,
} from './result-evolution';
import { convertValue, knownAnalyte, unitConversion } from './lab-unit-conversion';
import { serverInstant } from '../../shared/date-utils';

@Component({
  selector: 'app-result-evolution',
  imports: [
    FormsModule,
    DayMonthPipe,
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
        <!--
          r44: one short sentence; the method (exact conversions only, dates to
          scale) is behind the info button, available by pointer and keyboard.
        -->
        <p class="hint" data-testid="evolution-note">
          {{ 'LAB_EVOLUTION.NOTE_SHORT' | translate }}
          <span
            class="info"
            (mouseenter)="methodHover.set(true); methodDismissed.set(false)"
            (mouseleave)="methodHover.set(false)"
            (focusin)="methodFocus.set(true); methodDismissed.set(false)"
            (focusout)="methodFocus.set(false); methodPinned.set(false)"
          >
            <button
              type="button"
              class="info-btn"
              (click)="toggleMethod()"
              [attr.aria-expanded]="methodVisible()"
              [attr.aria-label]="'LAB_EVOLUTION.NOTE_INFO' | translate"
              [attr.aria-describedby]="resultId() + '-evolution-method'"
            >
              <span class="material-symbols-outlined" aria-hidden="true">info</span>
            </button>
            <span
              class="tooltip"
              [class.visible]="methodVisible()"
              role="tooltip"
              [id]="resultId() + '-evolution-method'"
              data-testid="evolution-method"
              >{{
                'LAB_EVOLUTION.NOTE_DETAIL' | translate: { unit: unit() ? ' (' + unit() + ')' : '' }
              }}
              @if (points().length > 1 && band()) {
                {{ 'LAB_EVOLUTION.BAND_NOTE' | translate }}
              }
            </span>
          </span>
        </p>
        @if (hasCorrections()) {
          <p class="hint">{{ 'LAB_EVOLUTION.CORRECTION_NOTE' | translate }}</p>
        }
        @if (points().length > 1) {
          <figure>
            <figcaption>{{ unit() }} · {{ 'LAB_EVOLUTION.TIME_AXIS' | translate }}</figcaption>
            <svg
              viewBox="0 0 600 240"
              role="img"
              [attr.aria-label]="'LAB_EVOLUTION.CHART_LABEL' | translate"
            >
              @if (band(); as b) {
                <!-- r44: the reference range applied to this patient, as a band. -->
                <rect
                  class="reference-band"
                  data-testid="evolution-reference-band"
                  x="55"
                  width="525"
                  [attr.y]="y(b.max)"
                  [attr.height]="y(b.min) - y(b.max)"
                >
                  <title>
                    {{ 'LAB_RANGE.REFERENCE' | translate }}{{ 'COMMON.COLON' | translate }}
                    {{ b.min | labValue }} – {{ b.max | labValue: unit() }}
                  </title>
                </rect>
              }
              @for (tick of axis().ticks; track tick) {
                <line class="grid" x1="55" x2="580" [attr.y1]="y(tick)" [attr.y2]="y(tick)" />
                <text class="tick" x="48" [attr.y]="y(tick) + 5" text-anchor="end">
                  {{ formatAxis(tick) }}
                </text>
              }
              <line x1="55" y1="20" x2="55" y2="205" />
              <line x1="55" y1="205" x2="580" y2="205" />
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
                    }}{{
                      p.converted
                        ? ' · ' +
                          ('LAB_EVOLUTION.CONVERTED_FROM'
                            | translate: { value: (p.resultValue | labValue: p.originalUnit) })
                        : ''
                    }}{{ p.corrected ? ' · ' + ('LAB_RESULTS.CORRECTED' | translate) : '' }}
                  </title>
                </circle>
              }
              <text x="55" y="232">
                {{ points()[0].resultDate | dayMonth: undefined : true }}
              </text>
              <text x="580" y="232" text-anchor="end">
                {{ points()[points().length - 1].resultDate | dayMonth: undefined : true }}
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
                      @if (convertedValue(p); as converted) {
                        <span class="converted" data-testid="evolution-converted">
                          ({{
                            'LAB_EVOLUTION.CONVERTED_TO'
                              | translate: { value: (converted | labValue: unit()) }
                          }})
                        </span>
                      }
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
    line.grid {
      stroke: #e1ebea;
    }
    .reference-band {
      fill: #88d7b7;
      fill-opacity: 0.35;
    }
    .info {
      position: relative;
      display: inline-block;
      vertical-align: middle;
    }
    .info-btn {
      border: 0;
      background: none;
      padding: 0 2px;
      cursor: help;
      color: #405a65;
      line-height: 1;
    }
    .info-btn .material-symbols-outlined {
      font-size: 16px;
    }
    .tooltip {
      display: none;
      position: absolute;
      z-index: 5;
      left: 50%;
      top: calc(100% + 4px);
      transform: translateX(-50%);
      width: min(320px, 80vw);
      padding: 8px 10px;
      border-radius: 6px;
      background: #193c46;
      color: white;
      font-size: 12px;
      line-height: 1.5;
    }
    .tooltip.visible {
      display: block;
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
    .converted {
      color: #405a65;
      font-style: italic;
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
  private readonly document = inject(DOCUMENT);
  constructor() {
    effect((onCleanup) => {
      if (!this.methodVisible()) return;
      // Hover does not move focus. Capture Escape before a containing dialog
      // handles it, even when the focused control is outside this component.
      const dismiss = (event: KeyboardEvent) => {
        if (event.key === 'Escape') this.dismissMethod(event);
      };
      this.document.addEventListener('keydown', dismiss, true);
      onCleanup(() => this.document.removeEventListener('keydown', dismiss, true));
    });
  }
  methodHover = signal(false);
  methodFocus = signal(false);
  methodPinned = signal(false);
  methodDismissed = signal(false);
  methodVisible = computed(
    () =>
      !this.methodDismissed() && (this.methodHover() || this.methodFocus() || this.methodPinned()),
  );
  toggleMethod(): void {
    const pinned = !this.methodPinned();
    this.methodPinned.set(pinned);
    this.methodDismissed.set(!pinned);
  }
  dismissMethod(event: Event): void {
    if (!this.methodVisible()) return;
    this.methodPinned.set(false);
    this.methodDismissed.set(true);
    event.stopPropagation();
  }
  history = input<LabResultTrendPoint[]>([]);
  loading = input(false);
  failed = input(false);
  resultId = input('');
  chosenUnit = linkedSignal({
    source: () => ({ id: this.resultId(), history: this.history(), unit: this.preferredUnit() }),
    computation: () => '',
  });
  preferredUnit = input('');
  /** r44: the range applied to this patient (gradedReferenceRange), drawn as a band. */
  referenceRange = input<LabResultReferenceRange | null | undefined>(null);
  /** The test's name and code: they say whether its molar mass is known (glucose). */
  testName = input<string | null | undefined>('');
  testCode = input<string | null | undefined>('');
  analyte = computed(() => knownAnalyte(this.testName(), this.testCode()));
  /** Recorded units, most recent first. */
  private recordedUnits = computed(() => [
    ...new Set(
      [...this.history()]
        .sort(
          (a, b) =>
            (serverInstant(b.resultDate).getTime() || 0) -
            (serverInstant(a.resultDate).getTime() || 0),
        )
        .map((p) => p.resultUnit?.trim())
        .filter((u): u is string => !!u),
    ),
  ]);
  /**
   * The display units a reader can choose: the preferred unit (the range's or the
   * result's) first when the history is in it or converts to it, then the recorded
   * units, most recent first. Choosing one plots every point that converts to it.
   */
  units = computed(() => {
    const recorded = this.recordedUnits();
    const preferred = this.preferredUnit().trim();
    const usable =
      !!preferred &&
      recorded.some((u) => u === preferred || unitConversion(u, preferred, this.analyte()));
    return usable ? [...new Set([preferred, ...recorded])] : recorded;
  });
  unit = computed(() =>
    this.units().includes(this.chosenUnit()) ? this.chosenUnit() : (this.units()[0] ?? ''),
  );
  points = computed(() => evolutionSeries(this.history(), this.unit(), this.analyte()));
  hasConversions = computed(() => this.points().some((p) => p.converted));
  /** The stored value in the plotted unit, when it was recorded in another one and converts. */
  convertedValue(point: LabResultTrendPoint): number | null {
    const original = point.resultUnit?.trim();
    if (!original || original === this.unit()) return null;
    const conversion = unitConversion(original, this.unit(), this.analyte());
    const value = exactObservation(point.resultValue);
    return conversion && Number.isFinite(value) ? convertValue(value, conversion) : null;
  }
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
    const precision = deltaPrecision(current.resultValue, previous.resultValue);
    return `${delta > 0 ? '+' : ''}${formatDecimal(delta, precision)} ${current.resultUnit.trim()}`;
  }
  linePoints = computed(() =>
    this.points()
      .map((p) => `${this.x(p.time)},${this.y(p.value)}`)
      .join(' '),
  );
  /**
   * r44: the applied range's limits in the plotted unit: as stated when the
   * units match, converted only by an exact factor, otherwise no band.
   */
  band = computed(() => {
    const range = this.referenceRange();
    const unit = this.unit();
    if (!range || range.minValue == null || range.maxValue == null || !unit) return null;
    const from = (range.unit ?? '').trim();
    if (!from) return null;
    let min = range.minValue,
      max = range.maxValue;
    if (from !== unit) {
      const conversion = unitConversion(from, unit, this.analyte());
      if (!conversion?.exact) return null;
      min = convertValue(min, conversion);
      max = convertValue(max, conversion);
    }
    return Number.isFinite(min) && Number.isFinite(max) && max > min ? { min, max } : null;
  });
  /** r44: round ticks covering the points and the band. */
  axis = computed(() => {
    const values = this.points().map((p) => p.value);
    const band = this.band();
    if (band) values.push(band.min, band.max);
    return (
      niceAxis(Math.min(...values), Math.max(...values)) ?? {
        min: 0,
        max: 1,
        step: 1,
        decimals: 0,
        ticks: [0, 1],
      }
    );
  });
  bounds = computed(() => ({ min: this.axis().min, max: this.axis().max }));
  formatAxis(value: number): string {
    return formatDecimal(value, this.axis().decimals);
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
