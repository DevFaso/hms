import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  untracked,
} from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

import { RoleContextService } from '../../core/role-context.service';
import { ClinicalRulesService, ruleText } from '../../services/clinical-rules.service';
import { DayMonthPipe } from '../pipes/day-month.pipe';

/**
 * V356 (owner direction 2026-11-21): where the portal says a rule is
 * « provisoire », it now says so for THIS hospital — « Provisoire — source :
 * … » while its approver has not signed it, « Validée le <date> par <titre> »
 * once they have. The rule behaves the same either way: only the label moves.
 *
 * ```html
 * <app-rule-status ruleKey="PRESCRIPTION_RENEWAL">{{ 'REFILLS.PROVISIONAL' | translate }}</app-rule-status>
 * ```
 *
 * The content between the tags is the screen's own wording, shown while the
 * hospital's status is not known (loading, or a role that does not read the
 * rules list), so a label never disappears. When that wording also says what
 * the rule does, pass that part as `detail`: it is kept before the status.
 */
@Component({
  selector: 'app-rule-status',
  standalone: true,
  imports: [TranslateModule, DayMonthPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (status(); as rule) {
      @if (compact()) {
        <span
          class="rule-status"
          data-testid="rule-status"
          [class.rule-status--signed]="rule.status === 'SIGNED'"
          [attr.data-rule]="rule.key"
          [attr.title]="
            rule.status === 'SIGNED' && rule.signoff
              ? ('CLINICAL_RULES.LABEL.SIGNED'
                | translate
                  : {
                      date: rule.signoff.signedOn | dayMonth: undefined : true,
                      title: rule.signoff.signerTitle,
                    })
              : ('CLINICAL_RULES.LABEL.PENDING'
                | translate: { source: sourceText(), approver: approver() })
          "
          >{{
            (rule.status === 'SIGNED'
              ? 'CLINICAL_RULES.STATUS.SIGNED'
              : 'CLINICAL_RULES.STATUS.PENDING'
            ) | translate
          }}</span
        >
      } @else {
        @if (detail()) {
          <span class="rule-status__detail">{{ detail() }}</span>
        }
        @if (rule.status === 'SIGNED' && rule.signoff) {
          <span
            class="rule-status rule-status--signed"
            data-testid="rule-status"
            [attr.data-rule]="rule.key"
          >
            <span class="material-symbols-outlined" aria-hidden="true">verified</span>
            {{
              'CLINICAL_RULES.LABEL.SIGNED'
                | translate
                  : {
                      date: rule.signoff.signedOn | dayMonth: undefined : true,
                      title: rule.signoff.signerTitle,
                    }
            }}
          </span>
        } @else {
          <span
            class="rule-status rule-status--pending"
            data-testid="rule-status"
            [attr.data-rule]="rule.key"
          >
            {{
              'CLINICAL_RULES.LABEL.PENDING'
                | translate: { source: sourceText(), approver: approver() }
            }}
          </span>
        }
      }
    } @else {
      <ng-content />
    }
  `,
  styles: [
    `
      :host {
        display: inline;
        min-width: 0;
        overflow-wrap: anywhere;
      }
      .rule-status {
        overflow-wrap: anywhere;
      }
      .rule-status--signed {
        color: var(--primary-deep);
        font-weight: 600;
      }
      .rule-status--signed .material-symbols-outlined {
        font-size: 1em;
        vertical-align: -0.125em;
      }
    `,
  ],
})
export class RuleStatusComponent {
  private readonly rules = inject(ClinicalRulesService);
  private readonly roleContext = inject(RoleContextService);

  /** A key of the backend's ClinicalRuleRegistry (ClinicalRuleSetKey). */
  readonly ruleKey = input.required<string>();
  /** What the rule does, kept before the status once it is known (the fallback content says it otherwise). */
  readonly detail = input<string | null>(null);
  /** A chip: the one word (« provisoire », « validée »), the full label in its tooltip. */
  readonly compact = input(false);

  private readonly hospitalId = computed(() => this.roleContext.effectiveHospitalIdForRequest());

  protected readonly status = computed(() => {
    // Read the cache signal so a sign-off recorded elsewhere moves the label.
    this.rules.overviewSignal();
    return this.rules.statusAt(this.hospitalId(), this.ruleKey());
  });
  protected readonly approver = computed(() => ruleText(this.status()?.approverRole));
  protected readonly sourceText = computed(() => ruleText(this.status()?.source));

  constructor() {
    effect(() => {
      const hospitalId = this.hospitalId();
      untracked(() => this.rules.ensureLoaded(hospitalId));
    });
  }
}
