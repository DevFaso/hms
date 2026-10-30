import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

/**
 * QA D1016 (the D931 class): a corrected lab result says so wherever it is
 * shown. D931 marked it on the laboratory's own /lab-results only, so the
 * patient portal, the in-basket table, the doctor's results panel, the chart
 * review and the release worklist kept showing "Négatif · Normal" as if it
 * were a first, unrelated result, when it replaced a "Positif" someone may
 * already have acted on.
 *
 * One badge for every screen. `corrected` is the API's own answer
 * (`corrected` on the patient, in-basket, snapshot and chart rows,
 * `supersedesResultId` on the laboratory's rows); `audience` picks the
 * explanation (a clinician reads that the old value stays on record, a
 * patient that the laboratory corrected a result they may have seen).
 *
 * `scripts/lib/lab-correction-shown.test.mjs` fails when a template shows a
 * lab result value without this badge.
 */
@Component({
  selector: 'app-lab-correction-badge',
  standalone: true,
  imports: [TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (corrected()) {
      <span
        class="lab-correction-badge"
        data-testid="lab-correction-badge"
        [title]="
          (audience() === 'patient'
            ? 'LAB_RESULTS.CORRECTED_HINT_PATIENT'
            : 'LAB_RESULTS.CORRECTED_HINT'
          ) | translate
        "
        >{{ 'LAB_RESULTS.CORRECTED' | translate }}</span
      >
    }
  `,
  styles: [
    `
      .lab-correction-badge {
        display: inline-block;
        margin: 0 0.25rem;
        padding: 0.05rem 0.45rem;
        border-radius: 999px;
        background: #e0f2fe;
        color: #075985;
        font-size: 0.75rem;
        font-weight: 600;
        white-space: nowrap;
      }
    `,
  ],
})
export class LabCorrectionBadgeComponent {
  readonly corrected = input<boolean | null | undefined>(false);
  readonly audience = input<'staff' | 'patient'>('staff');
}
