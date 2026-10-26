import {
  Component,
  OnInit,
  computed,
  inject,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';

import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { catchError, of } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';

import { SubscriptionService } from '../../services/subscription.service';
import { SubscriptionPlan, SubscriptionPlanRequest } from '../../services/subscription.model';
import { currentLocale, formatCfa } from '../../shared/i18n/app-locale';
import { refusalReason } from '../../shared/refusal-reason';

/**
 * QA D865: the currencies a plan can be priced in, as ISO 4217 codes. The
 * franc CFA first: it is the deployment's currency. A plan saved earlier in
 * another code keeps it as an extra option, so editing it does not change it.
 */
export const PLAN_CURRENCIES: readonly string[] = ['XOF', 'XAF', 'EUR', 'USD', 'GHS', 'NGN', 'GNF'];

/** Prices are stored in hundredths of the currency unit, whatever the currency. */
const HUNDREDTHS = 100;

const FRESH_REQUEST: SubscriptionPlanRequest = {
  name: '',
  tierCode: '',
  description: '',
  monthlyPriceCents: 0,
  currency: 'XOF',
  includedSeats: 0,
  featureKeys: '',
  active: true,
};

@Component({
  selector: 'app-super-admin-subscriptions',
  standalone: true,
  imports: [FormsModule, RouterLink, TranslateModule],
  templateUrl: './subscriptions.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './subscriptions.scss',
})
export class SubscriptionsComponent implements OnInit {
  private readonly service = inject(SubscriptionService);

  readonly loading = signal(true);
  readonly errored = signal(false);
  readonly plans = signal<SubscriptionPlan[]>([]);

  readonly showActiveOnly = signal(true);
  readonly editing = signal<SubscriptionPlan | null>(null);
  readonly form = signal<SubscriptionPlanRequest>({ ...FRESH_REQUEST });
  readonly formError = signal<string | null>(null);
  readonly formBusy = signal(false);
  // Explicit visibility flag — the form reset on "New plan" leaves every
  // field empty, so a derived "is form populated?" predicate would hide
  // the panel exactly when the user just asked to open it.
  readonly formOpen = signal(false);
  /** A refused deactivation, shown above the plans (the edit form may be closed). */
  readonly deactivateError = signal<string | null>(null);

  readonly hasPlans = computed(() => this.plans().length > 0);

  /**
   * QA D865: the price is typed in the currency's own unit (francs, euros),
   * not in hundredths, and a currency with no subunit takes whole amounts.
   */
  readonly priceStep = computed(() => (this.fractionDigits(this.form().currency) === 0 ? 1 : 0.01));

  /** The currency picker: the known codes, plus the plan's own when it is another one. */
  readonly currencyOptions = computed<string[]>(() => {
    const current = (this.form().currency ?? '').toUpperCase();
    return current && !PLAN_CURRENCIES.includes(current)
      ? [...PLAN_CURRENCIES, current]
      : [...PLAN_CURRENCIES];
  });

  /** The price the form shows, in the currency's unit. */
  readonly priceAmount = computed(() => this.form().monthlyPriceCents / HUNDREDTHS);

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.loading.set(true);
    this.errored.set(false);
    this.service
      .listPlans(this.showActiveOnly())
      .pipe(
        catchError(() => {
          this.errored.set(true);
          return of([] as SubscriptionPlan[]);
        }),
      )
      .subscribe((plans) => {
        this.plans.set(plans);
        this.loading.set(false);
      });
  }

  toggleActiveFilter(): void {
    this.showActiveOnly.update((v) => !v);
    this.refresh();
  }

  startCreate(): void {
    this.editing.set(null);
    this.form.set({ ...FRESH_REQUEST });
    this.formError.set(null);
    this.formOpen.set(true);
  }

  startEdit(plan: SubscriptionPlan): void {
    this.editing.set(plan);
    this.form.set({
      name: plan.name,
      tierCode: plan.tierCode,
      description: plan.description ?? '',
      monthlyPriceCents: plan.monthlyPriceCents,
      currency: plan.currency,
      includedSeats: plan.includedSeats,
      featureKeys: plan.featureKeys,
      active: plan.active,
    });
    this.formError.set(null);
    this.formOpen.set(true);
  }

  cancelEdit(): void {
    this.editing.set(null);
    this.form.set({ ...FRESH_REQUEST });
    this.formError.set(null);
    this.formOpen.set(false);
  }

  submit(): void {
    const body = this.form();
    if (!body.name?.trim() || !body.tierCode?.trim()) {
      this.formError.set('SUBSCRIPTIONS.ERROR.REQUIRED_FIELDS');
      return;
    }
    if (this.fractionDigits(body.currency) === 0 && body.monthlyPriceCents % HUNDREDTHS !== 0) {
      this.formError.set('SUBSCRIPTIONS.ERROR.WHOLE_UNITS');
      return;
    }
    this.formBusy.set(true);
    const target = this.editing();
    const obs = target ? this.service.updatePlan(target.id, body) : this.service.createPlan(body);
    obs
      .pipe(
        catchError((err: HttpErrorResponse) => {
          // The server's own reason (an unknown currency, a plan in use)
          // says what to fix; the generic text is the fallback.
          this.formError.set(refusalReason(err) ?? 'SUBSCRIPTIONS.ERROR.SAVE_FAILED');
          return of(null);
        }),
      )
      .subscribe((saved) => {
        this.formBusy.set(false);
        if (saved) {
          this.cancelEdit();
          this.refresh();
        }
      });
  }

  deactivate(plan: SubscriptionPlan): void {
    if (!plan.active) return;
    this.deactivateError.set(null);
    this.service.deactivatePlan(plan.id).subscribe({
      next: () => this.refresh(),
      // QA D850: a plan an organization still holds is refused (409) with
      // a reason naming how many; show it rather than a generic failure.
      error: (err: HttpErrorResponse) =>
        this.deactivateError.set(refusalReason(err) ?? 'SUBSCRIPTIONS.ERROR.DEACTIVATE_FAILED'),
    });
  }

  formatPrice(cents: number, currency: string): string {
    // QA D621: francs CFA read "F CFA" in every language.
    if ((currency || '').toUpperCase() === 'XOF') return formatCfa(cents / 100);
    try {
      return new Intl.NumberFormat(currentLocale(), { style: 'currency', currency }).format(
        cents / 100,
      );
    } catch {
      // An unknown ISO code would throw: keep the amount readable.
      return `${new Intl.NumberFormat(currentLocale(), { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(cents / 100)} ${currency}`;
    }
  }

  /** QA D865: the typed amount, in the currency's unit, stored in hundredths. */
  updatePrice(amount: number | string | null): void {
    const value = Number(amount);
    const hundredths = Number.isFinite(value) && value > 0 ? Math.round(value * HUNDREDTHS) : 0;
    this.updateForm('monthlyPriceCents', hundredths);
  }

  /** The currency's name in the reader's language ("franc CFA (BCEAO)"), or the code. */
  currencyLabel(code: string): string {
    try {
      const name = new Intl.DisplayNames([currentLocale()], { type: 'currency' }).of(code);
      return name && name !== code ? `${code} — ${name}` : code;
    } catch {
      return code;
    }
  }

  private fractionDigits(currency: string | undefined): number {
    try {
      return (
        new Intl.NumberFormat('en', {
          style: 'currency',
          currency: currency || 'XOF',
        }).resolvedOptions().maximumFractionDigits ?? 2
      );
    } catch {
      return 2;
    }
  }

  updateForm<K extends keyof SubscriptionPlanRequest>(
    key: K,
    value: SubscriptionPlanRequest[K],
  ): void {
    this.form.update((current) => ({ ...current, [key]: value }));
  }
}
