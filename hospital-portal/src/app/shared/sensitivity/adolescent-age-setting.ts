import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ToastService } from '../../core/toast.service';
import {
  AdolescentConfidentialityAge,
  SensitivePolicyService,
} from '../../services/sensitive-policy.service';
import { refusalReason } from '../refusal-reason';

/**
 * Policy 2026-11-20 (provisional): a hospital's adolescent confidentiality
 * age. From that age to 17, a minor's sensitive records are hidden from the
 * parents unless a clinician releases them. The default (12) is borrowed from
 * international practice: Burkina has no statutory age, so the card says it is
 * provisional and awaits a jurist.
 */
@Component({
  selector: 'app-adolescent-age-setting',
  standalone: true,
  imports: [FormsModule, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './adolescent-age-setting.scss',
  template: `
    @if (setting(); as current) {
      <section
        class="adolescent-age"
        data-testid="adolescent-age"
        aria-labelledby="adolescent-age-title"
      >
        <h2 class="adolescent-age__title" id="adolescent-age-title">
          <span class="material-symbols-outlined" aria-hidden="true">shield_lock</span>
          {{ 'SENSITIVE_POLICY.AGE.TITLE' | translate }}
        </h2>
        <p class="adolescent-age__hint">{{ 'SENSITIVE_POLICY.AGE.HINT' | translate }}</p>
        <p class="adolescent-age__provisional" role="note" data-testid="adolescent-age-provisional">
          {{ 'SENSITIVE_POLICY.AGE.PROVISIONAL' | translate: { age: current.defaultAge } }}
        </p>
        <div class="adolescent-age__row">
          <label class="adolescent-age__label" for="adolescent-age-input">
            {{ 'SENSITIVE_POLICY.AGE.LABEL' | translate }}
          </label>
          <input
            id="adolescent-age-input"
            class="adolescent-age__input"
            data-testid="adolescent-age-input"
            type="number"
            inputmode="numeric"
            min="0"
            max="18"
            step="1"
            [disabled]="saving()"
            [ngModel]="draft()"
            (ngModelChange)="draft.set($event)"
          />
          <button
            type="button"
            class="adolescent-age__btn adolescent-age__btn--primary"
            data-testid="adolescent-age-save"
            [disabled]="saving() || !valid() || draft() === current.effectiveAge"
            (click)="save()"
          >
            {{ 'COMMON.SAVE' | translate }}
          </button>
          @if (current.configuredAge !== null) {
            <button
              type="button"
              class="adolescent-age__btn"
              data-testid="adolescent-age-reset"
              [disabled]="saving()"
              (click)="reset()"
            >
              {{ 'SENSITIVE_POLICY.AGE.RESET' | translate: { age: current.defaultAge } }}
            </button>
          }
        </div>
        <p class="adolescent-age__state" data-testid="adolescent-age-state">
          @if (current.configuredAge === null) {
            {{ 'SENSITIVE_POLICY.AGE.USING_DEFAULT' | translate: { age: current.effectiveAge } }}
          } @else {
            {{ 'SENSITIVE_POLICY.AGE.USING_OWN' | translate: { age: current.effectiveAge } }}
          }
        </p>
      </section>
    }
  `,
})
export class AdolescentAgeSettingComponent {
  private readonly policy = inject(SensitivePolicyService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly hospitalId = input.required<string>();

  readonly setting = signal<AdolescentConfidentialityAge | null>(null);
  readonly draft = signal<number | null>(null);
  readonly saving = signal(false);

  constructor() {
    effect(() => {
      const id = this.hospitalId();
      this.setting.set(null);
      if (!id) return;
      this.policy.adolescentAge(id).subscribe({
        next: (current) => {
          if (id === this.hospitalId()) this.show(current);
        },
        // Not readable for this caller: the card is not shown.
        error: () => this.setting.set(null),
      });
    });
  }

  valid(): boolean {
    const age = this.draft();
    return typeof age === 'number' && Number.isInteger(age) && age >= 0 && age <= 18;
  }

  save(): void {
    if (!this.valid()) return;
    this.write(this.draft());
  }

  reset(): void {
    this.write(null);
  }

  private write(age: number | null): void {
    if (this.saving()) return;
    this.saving.set(true);
    this.policy.setAdolescentAge(this.hospitalId(), age).subscribe({
      next: (current) => {
        this.show(current);
        this.saving.set(false);
        this.toast.success(this.translate.instant('SENSITIVE_POLICY.AGE.SAVED'));
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('SENSITIVE_POLICY.AGE.SAVE_FAILED'),
        );
      },
    });
  }

  private show(current: AdolescentConfidentialityAge): void {
    this.setting.set(current);
    this.draft.set(current.effectiveAge);
  }
}
