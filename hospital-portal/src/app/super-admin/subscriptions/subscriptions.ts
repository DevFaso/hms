import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { catchError, of } from 'rxjs';

import { SubscriptionService } from '../../services/subscription.service';
import { SubscriptionPlan, SubscriptionPlanRequest } from '../../services/subscription.model';

const FRESH_REQUEST: SubscriptionPlanRequest = {
  name: '',
  tierCode: '',
  description: '',
  monthlyPriceCents: 0,
  currency: 'USD',
  includedSeats: 0,
  featureKeys: '',
  active: true,
};

@Component({
  selector: 'app-super-admin-subscriptions',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, TranslateModule],
  templateUrl: './subscriptions.html',
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

  readonly hasPlans = computed(() => this.plans().length > 0);

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
    this.formBusy.set(true);
    const target = this.editing();
    const obs = target ? this.service.updatePlan(target.id, body) : this.service.createPlan(body);
    obs
      .pipe(
        catchError(() => {
          this.formError.set('SUBSCRIPTIONS.ERROR.SAVE_FAILED');
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
    this.service.deactivatePlan(plan.id).subscribe({
      next: () => this.refresh(),
      error: () => this.formError.set('SUBSCRIPTIONS.ERROR.DEACTIVATE_FAILED'),
    });
  }

  formatPrice(cents: number, currency: string): string {
    const amount = (cents / 100).toFixed(2);
    return `${amount} ${currency}`;
  }

  updateForm<K extends keyof SubscriptionPlanRequest>(
    key: K,
    value: SubscriptionPlanRequest[K],
  ): void {
    this.form.update((current) => ({ ...current, [key]: value }));
  }
}
