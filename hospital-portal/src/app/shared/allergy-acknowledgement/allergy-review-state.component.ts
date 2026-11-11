import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

import { DateTimePipe } from '../pipes/date-time.pipe';
import { ServerTimePipe } from '../pipes/server-time.pipe';
import { AllergyReviewState } from './allergy-acknowledgement';

/**
 * QA D1584 / D1585: where an allergy conflict stands, shown beside its card at every gate (the eMAR row
 * and bedside check, the nurse-station MAR, the pharmacy work queue, verification, fill and hand-over).
 * The server reads it on the same token the gates check, so the screen can never say "awaiting review"
 * while the gate lets the dose through:
 * - awaiting review: an authorized prescriber must review the conflict first;
 * - CONTINUE: who reviewed it and when, with the prescriber's plan (and rationale) to give it under;
 * - STOP: the prescriber decided to stop, contact the prescriber.
 * The plan and the rationale reach only a clinical reader of the order at its own hospital; the
 * server leaves them out otherwise, and nothing is shown for them then.
 */
@Component({
  selector: 'app-allergy-review-state',
  standalone: true,
  imports: [TranslateModule, DateTimePipe, ServerTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (review(); as r) {
      <div
        class="allergy-review-state"
        [class.allergy-review-state--continue]="r.state === 'CONTINUE'"
        [class.allergy-review-state--stop]="r.state === 'STOP'"
        [class.allergy-review-state--awaiting]="r.state === 'AWAITING_REVIEW'"
        data-testid="allergy-review-state"
      >
        <p class="allergy-review-state__title" data-testid="allergy-review-state-title">
          <strong>{{ titleKey() | translate }}</strong>
        </p>
        @if (r.state !== 'AWAITING_REVIEW') {
          <p data-testid="allergy-review-state-by">
            {{
              (r.reviewedByName ? 'ALLERGY_REVIEW_STATE.BY' : 'ALLERGY_REVIEW_STATE.BY_UNKNOWN')
                | translate
                  : {
                      name: r.reviewedByName,
                      at: r.reviewedAt | serverTime | dateTime,
                      decision: decisionKey() | translate,
                    }
            }}
          </p>
        }
        @if (r.state === 'CONTINUE' && r.plan) {
          <p class="allergy-review-state__plan" data-testid="allergy-review-state-plan">
            <strong>{{ 'ALLERGY_REVIEW_STATE.PLAN' | translate }}</strong
            >{{ 'COMMON.COLON' | translate }}{{ r.plan }}
          </p>
        }
        @if (r.state !== 'AWAITING_REVIEW' && r.rationale) {
          <p data-testid="allergy-review-state-rationale">
            <strong>{{ 'ALLERGY_REVIEW_STATE.RATIONALE' | translate }}</strong
            >{{ 'COMMON.COLON' | translate }}{{ r.rationale }}
          </p>
        }
      </div>
    }
  `,
  styles: `
    .allergy-review-state {
      margin-top: 0.5rem;
      padding: 0.5rem 0.75rem;
      border-left: 4px solid var(--warning, #b45309);
      border-radius: 4px;
      background: var(--surface-2, transparent);
    }
    .allergy-review-state--continue {
      border-left-color: var(--info, #1d4ed8);
    }
    .allergy-review-state--stop {
      border-left-color: var(--danger, #b91c1c);
    }
    .allergy-review-state p {
      margin: 0.25rem 0;
      white-space: pre-line;
    }
  `,
})
export class AllergyReviewStateComponent {
  readonly review = input<AllergyReviewState | null | undefined>(null);

  /** The decision word of the "reviewed by" line. */
  protected readonly decisionKey = computed(() =>
    this.review()?.state === 'CONTINUE'
      ? 'ALLERGY_REVIEW_STATE.DECISION_CONTINUE'
      : 'ALLERGY_REVIEW_STATE.DECISION_STOP',
  );

  protected readonly titleKey = computed(() => {
    switch (this.review()?.state) {
      case 'CONTINUE':
        return 'ALLERGY_REVIEW_STATE.TITLE_CONTINUE';
      case 'STOP':
        return 'ALLERGY_REVIEW_STATE.TITLE_STOP';
      default:
        return 'ALLERGY_REVIEW_STATE.TITLE_AWAITING';
    }
  });
}
