import { Component, computed, input } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import { LabResultResponse } from '../../services/lab.service';
import { LabValuePipe } from './lab-value.pipe';
import { DateTimePipe } from '../../shared/pipes/date-time.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { resultRangePosition } from './result-range';
import { LabCorrectionBadgeComponent } from './lab-correction-badge.component';

@Component({
  selector: 'app-result-range',
  imports: [
    TranslateModule,
    LabValuePipe,
    DateTimePipe,
    ServerTimePipe,
    LabCorrectionBadgeComponent,
  ],
  template: `
    @if (position(); as point) {
      <section
        class="result-range"
        role="img"
        [attr.aria-labelledby]="id() + '-title'"
        [attr.aria-describedby]="id() + '-value ' + id() + '-limits ' + id() + '-date'"
      >
        <header>
          <h3 [id]="id() + '-title'">{{ 'LAB_RANGE.TITLE' | translate }}</h3>
          <time [id]="id() + '-date'" [attr.datetime]="result().resultDate">{{
            result().resultDate | serverTime | dateTime
          }}</time>
        </header>
        <div class="value-row" [id]="id() + '-value'">
          <strong>{{ result().resultValue | labValue: result().resultUnit }}</strong>
          <app-lab-correction-badge [corrected]="!!result().supersedesResultId" />
          <span [class]="'range-state ' + point.state">{{ stateLabel() | translate }}</span>
        </div>
        <div class="scale" aria-hidden="true">
          <div class="labels">
            <span>{{ 'LAB_RANGE.LOW' | translate }}</span
            ><span>{{ 'LAB_RANGE.NORMAL' | translate }}</span
            ><span>{{ 'LAB_RANGE.HIGH' | translate }}</span>
          </div>
          <div class="bar">
            <span class="low-band"></span><span class="normal-band"></span
            ><span class="high-band"></span>
            <span class="marker" [style.left.%]="point.percent"
              ><span class="triangle"></span
            ></span>
          </div>
          <div class="bounds">
            <span>{{ point.min | labValue }}</span
            ><span>{{ point.max | labValue }}</span>
          </div>
        </div>
        <p class="reference" [id]="id() + '-limits'">
          {{ 'LAB_RANGE.REFERENCE' | translate }}{{ 'COMMON.COLON' | translate }}
          <strong
            >{{ point.min | labValue }} – {{ point.max | labValue: result().resultUnit }}</strong
          >
        </p>
        @if (point.clipped) {
          <p class="scale-note">{{ 'LAB_RANGE.OUTSIDE_SCALE' | translate }}</p>
        }
      </section>
    } @else {
      <p class="range-unavailable">{{ 'LAB_RANGE.UNAVAILABLE' | translate }}</p>
    }
  `,
  styles: `
    :host {
      display: block;
      margin: 20px 0;
    }
    .result-range {
      border: 1px solid #c9dbdc;
      background: #fff;
      border-radius: 14px;
      padding: 20px;
      color: #173744;
    }
    header {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 10px;
      flex-wrap: wrap;
    }
    h3 {
      margin: 0;
      font-size: 14px;
    }
    time {
      color: #52677a;
      font-size: 12px;
    }
    .value-row {
      display: flex;
      align-items: center;
      gap: 12px;
      margin: 16px 0 20px;
      flex-wrap: wrap;
    }
    .value-row > strong {
      font-size: 24px;
      line-height: 1.3;
      overflow-wrap: anywhere;
    }
    .range-state {
      font-size: 12px;
      font-weight: 700;
      border-radius: 20px;
      padding: 5px 10px;
    }
    .range-state.low {
      color: #1e4d7c;
      background: #e4effc;
    }
    .range-state.normal {
      color: #145c49;
      background: #d8f4e8;
    }
    .range-state.high {
      color: #804000;
      background: #fff0d4;
    }
    .labels,
    .bar {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: 3px;
    }
    .labels {
      margin-bottom: 13px;
      color: #405a65;
      font-size: 12px;
      text-align: center;
      font-weight: 600;
    }
    .bar {
      position: relative;
      height: 14px;
    }
    .low-band {
      background: #bad6f6;
      border-radius: 7px 0 0 7px;
    }
    .normal-band {
      background: #88d7b7;
    }
    .high-band {
      background: #ffd18b;
      border-radius: 0 7px 7px 0;
    }
    .marker {
      position: absolute;
      top: -5px;
      height: 25px;
      width: 3px;
      background: #102f3c;
      transform: translateX(-50%);
      border: 1px solid white;
      border-radius: 2px;
      box-sizing: content-box;
    }
    .triangle {
      position: absolute;
      top: -6px;
      left: -4px;
      width: 0;
      height: 0;
      border-left: 5px solid transparent;
      border-right: 5px solid transparent;
      border-top: 6px solid #102f3c;
    }
    .bounds {
      position: relative;
      height: 24px;
      margin-top: 11px;
      font-size: 11px;
      color: #52677a;
    }
    .bounds span {
      position: absolute;
      transform: translateX(-50%);
    }
    .bounds span:first-child {
      left: 33.333%;
    }
    .bounds span:last-child {
      left: 66.667%;
    }
    .reference {
      margin: 4px 0 0;
      color: #405a65;
      font-size: 12px;
      line-height: 1.6;
    }
    .scale-note,
    .range-unavailable {
      color: #52677a;
      font-size: 12px;
      line-height: 1.6;
      margin: 9px 0 0;
    }
    @media (max-width: 420px) {
      .result-range {
        padding: 16px;
      }
      .value-row > strong {
        font-size: 21px;
      }
    }
  `,
})
export class ResultRangeComponent {
  result = input.required<LabResultResponse>();
  position = computed(() => resultRangePosition(this.result()));
  id = computed(() => 'result-range-' + this.result().id);
  stateLabel = computed(
    () =>
      ({ low: 'LAB_RANGE.LOW', normal: 'LAB_RANGE.NORMAL', high: 'LAB_RANGE.HIGH' })[
        this.position()?.state ?? 'normal'
      ],
  );
}
