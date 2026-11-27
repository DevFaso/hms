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

import { BillingService, InvoiceItemResponse, InvoiceStatus } from '../../services/billing.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { refusalReason } from '../../shared/refusal-reason';
import { refuseFields } from '../../shared/forms/invalid-fields';

/** The characters the server takes in a pre-authorisation number (InvoiceLinePreAuthorisations). */
export const PRE_AUTH_PATTERN = /^[\p{L}\p{N} ./_#:-]+$/u;
export const PRE_AUTH_MAX = 120;

/** The field id of a line's pre-authorisation number. */
export function preAuthFieldId(line: { id: string }): string {
  return `line-pre-auth-${line.id}`;
}

/**
 * Insurer claims PR 1 (QA D3984, US-1): a line's insurer pre-authorisation number on the invoice, and the small form
 * the desk and billing record or correct it with (hospital admin, accountant, billing specialist, reception; never
 * on a cancelled invoice). A line that bills an imaging order gives the order its number too (server side). The
 * server checks every step again.
 */
@Component({
  selector: 'app-line-pre-authorisation',
  standalone: true,
  imports: [FormControlStyleDirective, FormsModule, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!editing()) {
      @if (line().preAuthorisationNumber) {
        <div class="cell-secondary" [attr.data-testid]="'line-pre-auth-' + line().id">
          {{ 'INSURER_CLAIMS.LINE.VALUE' | translate: { number: line().preAuthorisationNumber } }}
        </div>
      }
      @if (canEdit()) {
        <button
          type="button"
          class="link-button"
          [attr.data-testid]="'line-pre-auth-edit-' + line().id"
          (click)="start()"
        >
          {{
            (line().preAuthorisationNumber
              ? 'INSURER_CLAIMS.LINE.CORRECT'
              : 'INSURER_CLAIMS.LINE.RECORD'
            ) | translate
          }}
        </button>
      }
    } @else {
      <form class="pre-auth-form" (ngSubmit)="save()">
        <label [for]="fieldId()">{{ 'INSURER_CLAIMS.LINE.LABEL' | translate }} *</label>
        <input
          appFormControl
          type="text"
          required
          [attr.maxlength]="max"
          [id]="fieldId()"
          name="authorizationNumber"
          autocomplete="off"
          [attr.data-testid]="'line-pre-auth-input-' + line().id"
          [ngModel]="draft()"
          (ngModelChange)="draft.set($event ?? '')"
        />
        <span class="pre-auth-actions">
          <button
            type="submit"
            class="btn-primary btn-sm"
            [disabled]="busy()"
            data-testid="line-pre-auth-save"
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
    }
    .link-button {
      padding: 0;
      border: 0;
      background: none;
      color: var(--primary);
      font: inherit;
      font-size: 0.8rem;
      text-decoration: underline;
      cursor: pointer;
    }
    .pre-auth-form {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
      margin-top: 0.35rem;
      input {
        max-width: 100%;
        min-width: 0;
      }
    }
    .pre-auth-actions {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem;
    }
  `,
})
export class LinePreAuthorisationComponent {
  private readonly billing = inject(BillingService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly invoiceId = input.required<string>();
  readonly invoiceStatus = input<InvoiceStatus | null | undefined>(null);
  readonly line = input.required<InvoiceItemResponse>();
  /** The number as recorded, so the page reloads its lines. */
  readonly saved = output<string>();

  readonly max = PRE_AUTH_MAX;
  readonly editing = signal(false);
  readonly draft = signal('');
  readonly busy = signal(false);

  /** The roles that record a number on the billing page (RoleValidator#canRecordPreAuthorisation). */
  private readonly recorder = this.roleContext.hasAnyActiveRole([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_ACCOUNTANT',
    'ROLE_BILLING_SPECIALIST',
    'ROLE_CLAIMS_REVIEWER',
    'ROLE_RECEPTIONIST',
  ]);
  readonly canEdit = computed(() => this.recorder && this.invoiceStatus() !== 'CANCELLED');
  readonly fieldId = computed(() => preAuthFieldId(this.line()));

  start(): void {
    this.draft.set(this.line().preAuthorisationNumber ?? '');
    this.editing.set(true);
  }

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
      .recordLinePreAuthorisation(this.invoiceId(), this.line().id, number)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (answer) => {
          this.busy.set(false);
          this.editing.set(false);
          this.toast.success(
            this.translate.instant(
              answer?.orderUpdated
                ? 'INSURER_CLAIMS.LINE.SAVED_WITH_ORDER'
                : 'INSURER_CLAIMS.LINE.SAVED',
            ),
          );
          this.saved.emit(answer?.preAuthorisationNumber ?? number);
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
