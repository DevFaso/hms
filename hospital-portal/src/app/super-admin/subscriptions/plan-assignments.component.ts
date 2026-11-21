import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ToastService } from '../../core/toast.service';
import { OrganizationResponse, OrganizationService } from '../../services/organization.service';
import { OrganizationSubscription, SubscriptionPlan } from '../../services/subscription.model';
import { SubscriptionService } from '../../services/subscription.service';
import { allRows } from '../../shared/all-pages';
import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';
import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { DateTimePipe } from '../../shared/pipes/date-time.pipe';
import { KeyLabelPipe } from '../../shared/pipes/key-label.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { OrgTextPipe } from '../../shared/pipes/org-text.pipe';
import { refusalReason, refusedFieldReasons } from '../../shared/refusal-reason';
import { refuseFields } from '../../shared/forms/invalid-fields';

type BillingPeriod = 'MONTHLY' | 'QUARTERLY' | 'ANNUAL';

/** The request properties the server may refuse, and the control each one is chosen in. */
const FIELD_BY_PROPERTY: Readonly<Record<string, string>> = {
  planId: 'pa-plan',
  seatLimit: 'pa-seats',
  billingPeriod: 'pa-period',
};
const FORM_FIELD_IDS: readonly string[] = Object.values(FIELD_BY_PROPERTY);

/**
 * QA r65 D2766: assign a plan to an organisation.
 *
 * <p>The page said « Catalogue de plans et affectations par organisation »
 * and offered no assignment anywhere: `POST /super-admin/subscriptions/organizations/{id}`
 * existed and the portal never called it. Here the super-admin picks an
 * organisation, sees the plan it is on (and what it was on before), assigns
 * one of the active plans (the server ends the current one in the same
 * transaction) or ends the current one.
 */
@Component({
  selector: 'app-plan-assignments',
  standalone: true,
  imports: [
    FormsModule,
    TranslateModule,
    SubmitGuardDirective,
    DateTimePipe,
    ServerTimePipe,
    KeyLabelPipe,
    OrgTextPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="pa-card" data-testid="plan-assignments" aria-labelledby="pa-title">
      <h2 id="pa-title" class="pa-title">{{ 'PLATFORM_SETUP.PLANS.TITLE' | translate }}</h2>
      <p class="pa-help">{{ 'PLATFORM_SETUP.PLANS.HELP' | translate }}</p>

      <div class="pa-field">
        <label for="pa-org">{{ 'PLATFORM_SETUP.PLANS.ORGANISATION' | translate }}</label>
        <select
          id="pa-org"
          data-testid="plan-assignments-organisation"
          [ngModel]="organisationId()"
          (ngModelChange)="selectOrganisation($event)"
        >
          <option value="">{{ 'PLATFORM_SETUP.PLANS.CHOOSE_ORGANISATION' | translate }}</option>
          @for (org of organisations(); track org.id) {
            <option [value]="org.id">{{ org.name | orgText: org.code }} ({{ org.code }})</option>
          }
        </select>
      </div>

      @if (loadFailed()) {
        <p class="pa-error" role="alert">{{ 'PLATFORM_SETUP.PLANS.LOAD_FAILED' | translate }}</p>
      }

      @if (organisationId() && !loading() && !loadFailed()) {
        <div class="pa-current" role="status" data-testid="plan-assignments-current">
          @if (current(); as sub) {
            <p class="pa-current-line">
              <strong>{{ 'PLATFORM_SETUP.PLANS.CURRENT' | translate }}</strong>
              <span class="pa-plan">{{ sub.planName || sub.planTierCode || '—' }}</span>
              {{ ' '
              }}<span class="pa-meta">
                {{
                  'PLATFORM_SETUP.PLANS.CURRENT_DETAIL'
                    | translate
                      : {
                          seats: sub.seatLimit,
                          period: (sub.billingPeriod | keyLabel: 'PLATFORM_SETUP.PLANS.PERIOD.'),
                          since: (sub.startedAt | serverTime | dateTime),
                        }
                }}
              </span>
            </p>
            <button
              type="button"
              class="btn-secondary"
              data-testid="plan-assignments-end"
              [disabled]="saving()"
              (click)="endCurrent(sub)"
            >
              {{ 'PLATFORM_SETUP.PLANS.END' | translate }}
            </button>
          } @else {
            <p class="pa-current-line" data-testid="plan-assignments-none">
              {{ 'PLATFORM_SETUP.PLANS.NO_PLAN' | translate }}
            </p>
          }
        </div>

        <form class="pa-form" (ngSubmit)="assign()">
          <div class="pa-field">
            <label for="pa-plan">{{ 'PLATFORM_SETUP.PLANS.PLAN' | translate }} *</label>
            <select
              id="pa-plan"
              name="planId"
              required
              data-testid="plan-assignments-plan"
              [ngModel]="planId()"
              (ngModelChange)="selectPlan($event)"
            >
              <option value="">{{ 'PLATFORM_SETUP.PLANS.CHOOSE_PLAN' | translate }}</option>
              @for (plan of activePlans(); track plan.id) {
                <option [value]="plan.id">{{ plan.name }} ({{ plan.tierCode }})</option>
              }
            </select>
          </div>
          <div class="pa-field pa-field-sm">
            <label for="pa-seats">{{ 'PLATFORM_SETUP.PLANS.SEATS' | translate }} *</label>
            <input
              id="pa-seats"
              name="seatLimit"
              type="number"
              min="0"
              step="1"
              required
              inputmode="numeric"
              data-testid="plan-assignments-seats"
              [ngModel]="seatLimit()"
              (ngModelChange)="seatLimit.set($event)"
            />
          </div>
          <div class="pa-field pa-field-sm">
            <label for="pa-period">{{ 'PLATFORM_SETUP.PLANS.BILLING_PERIOD' | translate }}</label>
            <select
              id="pa-period"
              name="billingPeriod"
              [ngModel]="billingPeriod()"
              (ngModelChange)="billingPeriod.set($event)"
            >
              @for (period of periods; track period) {
                <option [value]="period">
                  {{ 'PLATFORM_SETUP.PLANS.PERIOD.' + period | translate }}
                </option>
              }
            </select>
          </div>
          <button
            type="submit"
            class="btn-primary"
            data-testid="plan-assignments-assign"
            [disabled]="saving()"
            appSubmitGuard
            submitGuardRequired
          >
            {{
              (current() ? 'PLATFORM_SETUP.PLANS.CHANGE' : 'PLATFORM_SETUP.PLANS.ASSIGN')
                | translate
            }}
          </button>
        </form>
        @if (activePlans().length === 0) {
          <p class="pa-help" data-testid="plan-assignments-no-active-plan">
            {{ 'PLATFORM_SETUP.PLANS.NO_ACTIVE_PLAN' | translate }}
          </p>
        }

        @if (history().length > 0) {
          <h3 class="pa-subtitle">{{ 'PLATFORM_SETUP.PLANS.HISTORY' | translate }}</h3>
          <ul class="pa-history" data-testid="plan-assignments-history">
            @for (sub of history(); track sub.id) {
              <li>
                <span class="pa-plan">{{ sub.planName || sub.planTierCode || '—' }}</span>
                {{ ' '
                }}<span class="pa-meta">
                  {{
                    'PLATFORM_SETUP.PLANS.HISTORY_LINE'
                      | translate
                        : {
                            from: (sub.startedAt | serverTime | dateTime),
                            to: sub.endsAt ? (sub.endsAt | serverTime | dateTime) : '—',
                          }
                  }}
                </span>
              </li>
            }
          </ul>
        }
      }
    </section>
  `,
  styles: [
    `
      :host {
        display: block;
        min-width: 0;
        margin-top: 24px;
      }
      .pa-card {
        background: #fff;
        border: 1px solid var(--border);
        border-radius: 12px;
        padding: 16px;
        /* A long plan or organisation name wraps rather than widening the card. */
        overflow-wrap: anywhere;
      }
      .pa-form > *,
      .pa-current > * {
        min-width: 0;
        max-width: 100%;
      }
      .pa-title {
        margin: 0 0 4px;
        font-size: 1.0625rem;
        color: var(--text-primary);
      }
      .pa-subtitle {
        margin: 16px 0 6px;
        font-size: 0.9375rem;
        color: var(--text-primary);
      }
      .pa-help {
        margin: 0 0 12px;
        font-size: 0.875rem;
        color: var(--text-secondary);
      }
      .pa-field {
        display: grid;
        gap: 4px;
        flex: 1 1 220px;
        min-width: 0;
        margin-bottom: 12px;
      }
      .pa-field-sm {
        flex: 1 1 140px;
      }
      .pa-field label {
        font-size: 0.8125rem;
        font-weight: 600;
        color: var(--text-secondary);
      }
      .pa-field select,
      .pa-field input {
        width: 100%;
        min-width: 0;
        padding: 8px 10px;
        border: 1px solid var(--border);
        border-radius: 8px;
        font: inherit;
        background: #fff;
      }
      .pa-current {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        justify-content: space-between;
        gap: 8px 16px;
        padding: 10px 12px;
        margin-bottom: 12px;
        border-radius: 8px;
        background: var(--primary-soft);
      }
      .pa-current-line {
        margin: 0;
        display: flex;
        flex-wrap: wrap;
        gap: 4px 8px;
        min-width: 0;
        overflow-wrap: anywhere;
      }
      .pa-plan {
        font-weight: 600;
        color: var(--text-primary);
      }
      .pa-meta {
        font-size: 0.875rem;
        color: #475569;
      }
      .pa-form {
        display: flex;
        flex-wrap: wrap;
        align-items: flex-end;
        gap: 0 12px;
      }
      .pa-form button {
        margin-bottom: 12px;
      }
      .pa-history {
        list-style: none;
        margin: 0;
        padding: 0;
        display: grid;
        gap: 4px;
      }
      .pa-history li {
        display: flex;
        flex-wrap: wrap;
        gap: 4px 8px;
        overflow-wrap: anywhere;
      }
      .pa-error {
        margin: 0 0 12px;
        padding: 8px 12px;
        border-radius: 8px;
        background: #fef2f2;
        color: #991b1b;
        font-size: 0.875rem;
      }
    `,
  ],
})
export class PlanAssignmentsComponent implements OnInit {
  private readonly subscriptions = inject(SubscriptionService);
  private readonly organisationService = inject(OrganizationService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly confirmService = inject(ConfirmService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** The catalogue the page already loaded; only active plans can be assigned (the server refuses the rest). */
  readonly plans = input<SubscriptionPlan[]>([]);

  readonly periods: readonly BillingPeriod[] = ['MONTHLY', 'QUARTERLY', 'ANNUAL'];

  readonly organisations = signal<OrganizationResponse[]>([]);
  readonly organisationId = signal('');
  readonly rows = signal<OrganizationSubscription[]>([]);
  readonly loading = signal(false);
  readonly loadFailed = signal(false);
  readonly saving = signal(false);

  readonly planId = signal('');
  readonly seatLimit = signal<number | null>(null);
  readonly billingPeriod = signal<BillingPeriod>('MONTHLY');

  readonly activePlans = computed(() => this.plans().filter((plan) => plan.active));
  readonly current = computed(() => this.rows().find((sub) => sub.status === 'ACTIVE') ?? null);
  /** Ended subscriptions, most recent first. */
  readonly history = computed(() =>
    this.rows()
      .filter((sub) => sub.status !== 'ACTIVE')
      .sort((a, b) => (b.startedAt ?? '').localeCompare(a.startedAt ?? '')),
  );

  ngOnInit(): void {
    // Every active organisation (all pages), as the hospital form lists them.
    allRows((page, size) => this.organisationService.list(page, size, true)).subscribe({
      next: (list) => this.organisations.set(list),
      error: () => this.loadFailed.set(true),
    });
  }

  selectOrganisation(id: string): void {
    this.organisationId.set(id ?? '');
    this.rows.set([]);
    this.planId.set('');
    this.seatLimit.set(null);
    this.reload();
  }

  /** Choosing a plan proposes its own seat count; the super-admin may change it. */
  selectPlan(id: string): void {
    this.planId.set(id ?? '');
    const plan = this.activePlans().find((candidate) => candidate.id === id);
    if (plan) this.seatLimit.set(plan.includedSeats);
  }

  assign(): void {
    const organisationId = this.organisationId();
    const planId = this.planId();
    const seats = Number(this.seatLimit());
    if (!organisationId || !planId || this.saving()) return;
    if (!Number.isInteger(seats) || seats < 0) {
      // Marked under the field (and focused), never a toast alone.
      refuseFields(
        this.host.nativeElement,
        ['pa-seats'],
        FORM_FIELD_IDS,
        this.translate.instant('PLATFORM_SETUP.PLANS.SEATS_INVALID'),
      );
      return;
    }
    refuseFields(this.host.nativeElement, [], FORM_FIELD_IDS);
    this.saving.set(true);
    this.subscriptions
      .assignPlan(organisationId, {
        planId,
        seatLimit: seats,
        billingPeriod: this.billingPeriod(),
      })
      .subscribe({
        next: (assigned) => {
          this.saving.set(false);
          this.toast.success(
            this.translate.instant('PLATFORM_SETUP.PLANS.ASSIGNED', {
              plan: assigned.planName ?? assigned.planTierCode ?? '',
            }),
          );
          this.planId.set('');
          this.seatLimit.set(null);
          this.reload();
        },
        error: (err: unknown) => {
          this.saving.set(false);
          // A refusal that names a field (a deactivated plan, a seat count) is marked on it.
          const refused = refusedFieldReasons(err, FIELD_BY_PROPERTY);
          if (refused.length > 0) {
            refuseFields(
              this.host.nativeElement,
              refused.map(([id, message]) => ({ id, message })),
              FORM_FIELD_IDS,
            );
            return;
          }
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('PLATFORM_SETUP.PLANS.ASSIGN_FAILED'),
          );
        },
      });
  }

  async endCurrent(sub: OrganizationSubscription): Promise<void> {
    const confirmed = await this.confirmService.ask({
      titleKey: 'PLATFORM_SETUP.PLANS.END_CONFIRM',
      params: { plan: sub.planName ?? sub.planTierCode ?? '' },
    });
    if (!confirmed || this.saving()) return;
    this.saving.set(true);
    this.subscriptions.cancel(sub.organizationId, sub.id).subscribe({
      next: () => {
        this.saving.set(false);
        this.toast.success(this.translate.instant('PLATFORM_SETUP.PLANS.ENDED'));
        this.reload();
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('PLATFORM_SETUP.PLANS.END_FAILED'),
        );
      },
    });
  }

  private reload(): void {
    const organisationId = this.organisationId();
    this.loadFailed.set(false);
    if (!organisationId) {
      this.rows.set([]);
      return;
    }
    this.loading.set(true);
    this.subscriptions.listForOrganization(organisationId).subscribe({
      next: (rows) => {
        // An answer for an organisation the picker has since left is dropped.
        if (this.organisationId() !== organisationId) return;
        this.rows.set(rows ?? []);
        this.loading.set(false);
      },
      error: () => {
        if (this.organisationId() !== organisationId) return;
        this.loading.set(false);
        this.loadFailed.set(true);
      },
    });
  }
}
