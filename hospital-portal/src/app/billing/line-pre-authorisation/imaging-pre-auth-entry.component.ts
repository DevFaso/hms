import { FormControlStyleDirective } from '../../shared/forms/form-control-style.directive';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { BillingService } from '../../services/billing.service';
import { ToastService } from '../../core/toast.service';
import { refusalReason } from '../../shared/refusal-reason';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { PRE_AUTH_MAX, PRE_AUTH_PATTERN } from './line-pre-authorisation.component';

/**
 * Insurer claims PR 1 (QA D3984, US-1): the billing follow-up of an urgent study done without its pre-authorisation
 * number (in-basket task IMAGING_PREAUTH) is answered where it is read: billing types the number the insurer gave.
 * The server records it on the order (no clinical field comes back), copies it onto the order's invoice lines and
 * closes the task.
 */
@Component({
  selector: 'app-imaging-pre-auth-entry',
  standalone: true,
  imports: [FormControlStyleDirective, FormsModule, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!editing()) {
      <button
        type="button"
        class="btn-secondary btn-sm btn-review"
        data-testid="in-basket-pre-auth"
        (click)="editing.set(true)"
      >
        {{ 'INSURER_CLAIMS.LINE.RECORD' | translate }}
      </button>
    } @else {
      <form class="pre-auth-entry" (ngSubmit)="save()">
        <label [for]="fieldId()">{{ 'INSURER_CLAIMS.LINE.LABEL' | translate }} *</label>
        <input
          appFormControl
          type="text"
          required
          autocomplete="off"
          [attr.maxlength]="max"
          [id]="fieldId()"
          name="authorizationNumber"
          data-testid="in-basket-pre-auth-input"
          [ngModel]="draft()"
          (ngModelChange)="draft.set($event ?? '')"
        />
        <span class="pre-auth-entry-actions">
          <button
            type="submit"
            class="btn-primary btn-sm"
            data-testid="in-basket-pre-auth-save"
            [disabled]="busy()"
          >
            {{ 'COMMON.SAVE' | translate }}
          </button>
          <button type="button" class="btn-secondary btn-sm" (click)="editing.set(false)">
            {{ 'COMMON.CANCEL' | translate }}
          </button>
        </span>
      </form>
    }
  `,
  styles: `
    :host {
      display: block;
      min-width: 0;
      max-width: 100%;
    }
    .pre-auth-entry {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
      input {
        max-width: 100%;
        min-width: 0;
      }
    }
    .pre-auth-entry-actions {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem;
    }
  `,
})
export class ImagingPreAuthEntryComponent {
  private readonly billing = inject(BillingService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly orderId = input.required<string>();
  readonly saved = output<void>();

  readonly max = PRE_AUTH_MAX;
  readonly editing = signal(false);
  readonly draft = signal('');
  readonly busy = signal(false);
  readonly fieldId = computed(() => `imaging-pre-auth-${this.orderId()}`);

  save(): void {
    if (this.busy()) return;
    const number = this.draft().trim().replace(/\s+/g, ' ');
    let problem: string | null = null;
    if (!number) {
      problem = this.translate.instant('INSURER_CLAIMS.LINE.REQUIRED');
    } else if (number.length > PRE_AUTH_MAX || !PRE_AUTH_PATTERN.test(number)) {
      problem = this.translate.instant('INSURER_CLAIMS.LINE.CHARACTERS');
    }
    if (problem) {
      const id = this.fieldId();
      refuseFields(this.host.nativeElement, [id], [id], problem);
      this.toast.error(problem);
      return;
    }
    this.busy.set(true);
    this.billing
      .recordImagingPreAuthorisation(this.orderId(), number)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.editing.set(false);
          this.toast.success(this.translate.instant('INSURER_CLAIMS.LINE.SAVED_WITH_ORDER'));
          this.saved.emit();
        },
        error: (err: unknown) => {
          this.busy.set(false);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('INSURER_CLAIMS.LINE.FAILED'),
          );
        },
      });
  }
}
