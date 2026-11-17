import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

import { MarDoseHistoryEntry } from '../../services/nurse-task.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';

/**
 * QA D1225 (round 24): the closed doses of a MAR row, with the reasons the
 * administer call required — why a dose was held or refused, why it was given
 * before the minimum interval (QA D1210), why a failed five-rights check was
 * overridden — and who gave and witnessed it. Those reasons were stored and
 * shown on no screen; the next nurse read "Dernière dose : Administrée" with
 * no hint that it was given early, or why.
 *
 * Rendered on every surface that reads a MAR row: the nurse-station
 * administration register and the eMAR detail.
 */
@Component({
  selector: 'app-mar-dose-history',
  standalone: true,
  imports: [DatePipe, TranslateModule, EnumLabelPipe, ServerTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (doses().length) {
      <details class="mar-history" [open]="expanded()" data-testid="mar-dose-history">
        <summary>{{ 'MAR_HISTORY.TITLE' | translate }} ({{ doses().length }})</summary>
        <ul class="mar-history__list">
          @for (d of doses(); track d.id) {
            <li class="mar-history__dose" [attr.data-testid]="'mar-dose-' + d.id">
              <span class="mar-history__status">{{
                d.status | enumLabel: 'medicationAdministrationStatus'
              }}</span>
              @if (d.givenEarly) {
                {{ ' '
                }}<span class="mar-history__early" [attr.data-testid]="'mar-dose-early-' + d.id">{{
                  'MAR_HISTORY.EARLY' | translate
                }}</span>
              }
              @if (d.recordedAt) {
                {{ ' '
                }}<span class="mar-history__time">{{
                  d.recordedAt | serverTime | date: 'short'
                }}</span>
              }
              @if (d.administeredBy) {
                {{ ' '
                }}<span class="mar-history__by">{{
                  'MAR_HISTORY.BY' | translate: { name: d.administeredBy }
                }}</span>
              }
              @if (d.reason) {
                <p class="mar-history__reason" [attr.data-testid]="'mar-dose-reason-' + d.id">
                  {{ 'MAR_HISTORY.REASON' | translate }} {{ d.reason }}
                </p>
              }
              @if (d.overrideReason) {
                <p class="mar-history__reason" [attr.data-testid]="'mar-dose-override-' + d.id">
                  {{ 'MAR_HISTORY.OVERRIDE' | translate }} {{ d.overrideReason }}
                </p>
              }
              @if (d.witnessName) {
                <p class="mar-history__witness">
                  {{ 'MAR_HISTORY.WITNESS' | translate }} {{ d.witnessName }}
                </p>
              }
            </li>
          }
        </ul>
      </details>
    }
  `,
  styles: `
    .mar-history {
      margin-top: 0.25rem;
      font-size: 0.85rem;
    }
    .mar-history summary {
      cursor: pointer;
    }
    .mar-history__list {
      margin: 0.25rem 0 0;
      padding-left: 1rem;
    }
    .mar-history__dose {
      margin-bottom: 0.25rem;
    }
    .mar-history__status {
      font-weight: 600;
    }
    .mar-history__early {
      padding: 0 0.35rem;
      border-radius: 0.5rem;
      background: var(--warning-bg, #fff4e5);
      color: var(--warning-text, #7a4b00);
    }
    .mar-history__reason,
    .mar-history__witness {
      margin: 0.1rem 0 0;
    }
  `,
})
export class MarDoseHistoryComponent {
  /** The row's closed doses, newest first (the server's order). */
  readonly doses = input<MarDoseHistoryEntry[]>([]);
  /** Open on render (the eMAR detail) rather than folded (the register row). */
  readonly expanded = input(false);
}
