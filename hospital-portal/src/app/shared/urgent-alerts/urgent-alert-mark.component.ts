import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import { UrgentAlertsService } from '../../services/urgent-alerts.service';
import { urgentAlertAge } from './urgent-alert-age';

/**
 * QA D4101 (r81): the mark of a patient with an urgent alert nobody has acknowledged, on the doctor's worklist
 * row and on the bed-board card: how many alerts wait and since when the oldest waits. A patient whose NEWS2 16
 * had been unanswered for an hour carried nothing on either screen.
 *
 * It reads the marks its host screen loaded (`UrgentAlertsService.refreshMarks`); with no mark for the patient it
 * renders nothing. It carries no clinical text.
 */
@Component({
  selector: 'app-urgent-alert-mark',
  standalone: true,
  imports: [TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (mark(); as m) {
      <span
        class="urgent-alert-mark"
        role="img"
        data-testid="urgent-alert-mark"
        [attr.aria-label]="
          (m.open > 1 ? 'URGENT_ALERTS.MARK_MANY' : 'URGENT_ALERTS.MARK_ONE')
            | translate: { count: m.open, age: ageText() }
        "
        [attr.title]="
          (m.open > 1 ? 'URGENT_ALERTS.MARK_MANY' : 'URGENT_ALERTS.MARK_ONE')
            | translate: { count: m.open, age: ageText() }
        "
      >
        <span class="material-symbols-outlined" aria-hidden="true">emergency</span>
        <span aria-hidden="true">{{ m.open }}</span>
        @if (age(); as a) {
          <span aria-hidden="true">· {{ a.key | translate: a.params }}</span>
        }
      </span>
    }
  `,
  styles: `
    .urgent-alert-mark {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 2px 8px;
      border-radius: 999px;
      background: #b91c1c;
      color: #fff;
      font-size: 0.75rem;
      font-weight: 700;
      line-height: 1.4;
      white-space: nowrap;
      vertical-align: middle;
    }
    .urgent-alert-mark .material-symbols-outlined {
      font-size: 14px;
    }
  `,
})
export class UrgentAlertMarkComponent {
  private readonly alerts = inject(UrgentAlertsService);

  readonly patientId = input<string | null | undefined>(null);
  /** The clock the age is read against; the host passes nothing and gets the time of the render. */
  readonly now = input<Date>(new Date());

  readonly mark = computed(() => {
    const id = this.patientId();
    const mark = id ? this.alerts.marks().get(id) : undefined;
    return mark && mark.open > 0 ? mark : null;
  });

  readonly age = computed(() => urgentAlertAge(this.mark()?.oldestRaisedAt, this.now()));

  /** The age as words for the accessible name; empty when the time is unknown. */
  ageText(): string {
    const age = this.age();
    if (!age) return '';
    return age.key === 'URGENT_ALERTS.AGE_HOURS'
      ? `${age.params.hours} h ${age.params.minutes} min`
      : `${age.params.minutes} min`;
  }
}
