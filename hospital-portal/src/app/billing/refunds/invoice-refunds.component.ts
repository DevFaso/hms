import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  BillingInvoiceResponse,
  BillingService,
  InvoiceRefund,
  MOBILE_MONEY_OPERATORS,
  REFUND_METHODS,
  RefundMethod,
} from '../../services/billing.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { CfaPipe } from '../../shared/pipes/cfa.pipe';
import { KeyLabelPipe } from '../../shared/pipes/key-label.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { DialogA11yDirective } from '../../shared/a11y/dialog-a11y.directive';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { markServerFieldErrors } from '../../shared/forms/server-field-errors';
import { refusalReason } from '../../shared/refusal-reason';
import { joinReasons } from '../../shared/required-fields';
import { saveResponse } from '../../shared/download-file';
import { cfaAmountMessageKey, cfaAmountProblem } from '../../shared/cfa-amount';

/** The request form's fields, in the order a refusal marks them. */
const AMOUNT_ID = 'refund-amount';
const METHOD_ID = 'refund-method';
const OPERATOR_ID = 'refund-operator';
const REFERENCE_ID = 'refund-reference';
const REASON_ID = 'refund-reason';
const REQUEST_FIELD_IDS = [AMOUNT_ID, METHOD_ID, OPERATOR_ID, REFERENCE_ID, REASON_ID];
const DECISION_REASON_ID = 'refund-decision-reason';

/** Methods whose money is traced only by the operator's or the bank's reference (as the server requires). */
const REFERENCE_REQUIRED: readonly string[] = ['MOBILE_MONEY', 'BANK_TRANSFER'];

/** A fresh idempotency key for one refund request (kept across retries of the same dialog). */
function newRequestKey(): string {
  return (
    globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`
  );
}

/**
 * Billing-money plan PR 8 (QA D1900): the refunds of one invoice, on its detail panel.
 *
 * <p>"Rembourser" opens the request when something is owed back ({@code refundDue > 0}); the list ("Demandes de
 * remboursement") shows each refund with who asked, who decided and who handed it over. A hospital admin or an
 * accountant who did NOT ask approves or rejects it (owner decision D-3); the cashier, the front desk or an admin
 * records the hand-over ("Remis au patient"); a refund handed over has its receipt. The forms never take card
 * details: a notice says so and the server refuses a card number.
 */
@Component({
  selector: 'app-invoice-refunds',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslateModule,
    CfaPipe,
    KeyLabelPipe,
    ServerTimePipe,
    DialogA11yDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './invoice-refunds.component.html',
  styleUrl: './invoice-refunds.component.scss',
})
export class InvoiceRefundsComponent {
  private readonly billing = inject(BillingService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** The invoice whose refunds are shown. */
  readonly invoice = input.required<BillingInvoiceResponse>();
  /** A refund step moved money or its state: the parent re-reads the invoice. */
  readonly changed = output<void>();

  readonly methods = REFUND_METHODS;
  readonly operators = MOBILE_MONEY_OPERATORS;

  /** The server's roles for each step (InvoiceRefundController); the server checks the assignment again. */
  readonly canRequest = this.roleContext.hasAnyActiveRole([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_ACCOUNTANT',
    'ROLE_BILLING_SPECIALIST',
    'ROLE_RECEPTIONIST',
  ]);
  readonly canApprove = this.roleContext.hasAnyActiveRole([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_ACCOUNTANT',
  ]);
  readonly canPay = this.roleContext.hasAnyActiveRole([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_BILLING_SPECIALIST',
    'ROLE_RECEPTIONIST',
  ]);

  readonly refunds = signal<InvoiceRefund[]>([]);
  readonly loading = signal(false);
  readonly loadFailed = signal(false);
  readonly busy = signal(false);

  /** "Rembourser" shows only when something is owed back to the patient. */
  readonly refundDue = computed(() => Number(this.invoice().refundDue ?? 0));
  readonly showRequestButton = computed(() => this.canRequest && this.refundDue() > 0);

  /* ── the request dialog ── */
  readonly requestOpen = signal(false);
  readonly amount = signal<number | null>(null);
  readonly method = signal<RefundMethod>('CASH');
  readonly operator = signal('');
  readonly reference = signal('');
  readonly reason = signal('');
  private requestKey = newRequestKey();
  readonly needsOperator = computed(() => this.method() === 'MOBILE_MONEY');
  readonly needsReference = computed(() => REFERENCE_REQUIRED.includes(this.method()));

  /* ── the reject / cancel dialog ── */
  readonly decision = signal<{ refund: InvoiceRefund; mode: 'reject' | 'cancel' } | null>(null);
  readonly decisionReason = signal('');

  private request = 0;

  constructor() {
    effect(() => {
      const id = this.invoice().id;
      untracked(() => this.load(id));
    });
  }

  /** Approve or reject: an approver who did not ask for it, while it waits for a decision. */
  canDecide(r: InvoiceRefund): boolean {
    return this.canApprove && r.status === 'REQUESTED' && !r.requestedByMe;
  }

  /** The approver sees why they cannot decide their own request. */
  isOwnRequest(r: InvoiceRefund): boolean {
    return this.canApprove && r.status === 'REQUESTED' && !!r.requestedByMe;
  }

  canHandOver(r: InvoiceRefund): boolean {
    return this.canPay && r.status === 'APPROVED';
  }

  canCancel(r: InvoiceRefund): boolean {
    if (r.status === 'REQUESTED') return !!r.requestedByMe;
    if (r.status === 'APPROVED') return !!r.requestedByMe || this.canApprove;
    return false;
  }

  load(invoiceId: string): void {
    const seq = ++this.request;
    this.loading.set(true);
    this.loadFailed.set(false);
    this.billing
      .listRefunds(invoiceId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => {
          if (seq !== this.request) return;
          this.refunds.set(rows ?? []);
          this.loading.set(false);
        },
        error: () => {
          if (seq !== this.request) return;
          this.refunds.set([]);
          this.loadFailed.set(true);
          this.loading.set(false);
        },
      });
  }

  openRequest(): void {
    this.amount.set(this.refundDue() > 0 ? this.refundDue() : null);
    this.method.set('CASH');
    this.operator.set('');
    this.reference.set('');
    this.reason.set('');
    this.requestKey = newRequestKey();
    this.requestOpen.set(true);
  }

  closeRequest(): void {
    this.requestOpen.set(false);
  }

  submitRequest(): void {
    if (this.busy()) return;
    const refused: { id: string; message: string }[] = [];
    const amount = this.amount();
    const amountProblem = cfaAmountProblem(amount);
    if (amountProblem) {
      refused.push({
        id: AMOUNT_ID,
        message: this.translate.instant(
          cfaAmountMessageKey(amountProblem, 'BILLING.REFUNDS.AMOUNT_REQUIRED'),
        ),
      });
    }
    if (this.needsOperator() && !this.operator()) {
      refused.push({
        id: OPERATOR_ID,
        message: this.translate.instant('BILLING.REFUNDS.OPERATOR_REQUIRED'),
      });
    }
    if (this.needsReference() && !this.reference().trim()) {
      refused.push({
        id: REFERENCE_ID,
        message: this.translate.instant('BILLING.REFUNDS.REFERENCE_REQUIRED'),
      });
    }
    if (!this.reason().trim()) {
      refused.push({
        id: REASON_ID,
        message: this.translate.instant('BILLING.REFUNDS.REASON_REQUIRED'),
      });
    }
    if (refused.length > 0) {
      // The submit is never dead: every missing field is marked where it is typed, the first one focused.
      // QA D1256 class: every reason said at once, in one message too.
      refuseFields(this.host.nativeElement, refused, REQUEST_FIELD_IDS);
      this.toast.error(joinReasons(refused.map((r) => r.message)) ?? '');
      return;
    }
    const invoiceId = this.invoice().id;
    this.busy.set(true);
    this.billing
      .requestRefund(invoiceId, {
        amount,
        method: this.method(),
        mobileMoneyOperator: this.needsOperator() ? this.operator() : null,
        reference: this.reference().trim() || null,
        reason: this.reason().trim(),
        clientRequestId: this.requestKey,
      })
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.requestOpen.set(false);
          this.toast.success(this.translate.instant('BILLING.REFUNDS.REQUESTED_TOAST'));
          this.afterStep(invoiceId);
        },
        error: (err: unknown) => {
          this.busy.set(false);
          // The server's refusal is marked on its field (amount above what is left, a card number...).
          const marked = markServerFieldErrors(this.host.nativeElement, err, {
            amount: AMOUNT_ID,
            method: METHOD_ID,
            mobileMoneyOperator: OPERATOR_ID,
            reference: REFERENCE_ID,
            reason: REASON_ID,
          });
          if (marked.length === 0) {
            this.toast.error(
              refusalReason(err) ?? this.translate.instant('BILLING.REFUNDS.FAILED'),
            );
          }
        },
      });
  }

  approve(r: InvoiceRefund): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.billing
      .approveRefund(r.invoiceId, r.id)
      .subscribe(this.stepObserver(r, 'BILLING.REFUNDS.APPROVED_TOAST'));
  }

  handOver(r: InvoiceRefund): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.billing
      .payRefund(r.invoiceId, r.id)
      .subscribe(this.stepObserver(r, 'BILLING.REFUNDS.PAID_TOAST'));
  }

  openDecision(r: InvoiceRefund, mode: 'reject' | 'cancel'): void {
    this.decisionReason.set('');
    this.decision.set({ refund: r, mode });
  }

  closeDecision(): void {
    this.decision.set(null);
  }

  submitDecision(): void {
    const open = this.decision();
    if (!open || this.busy()) return;
    const reason = this.decisionReason().trim();
    if (!reason) {
      refuseFields(
        this.host.nativeElement,
        [DECISION_REASON_ID],
        [DECISION_REASON_ID],
        this.translate.instant('BILLING.REFUNDS.DECISION_REASON_REQUIRED'),
      );
      return;
    }
    this.busy.set(true);
    const r = open.refund;
    const call =
      open.mode === 'reject'
        ? this.billing.rejectRefund(r.invoiceId, r.id, reason)
        : this.billing.cancelRefund(r.invoiceId, r.id, reason);
    call.subscribe({
      next: () => {
        this.busy.set(false);
        this.decision.set(null);
        this.toast.success(
          this.translate.instant(
            open.mode === 'reject'
              ? 'BILLING.REFUNDS.REJECTED_TOAST'
              : 'BILLING.REFUNDS.CANCELLED_TOAST',
          ),
        );
        this.afterStep(r.invoiceId);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        const marked = markServerFieldErrors(this.host.nativeElement, err, {
          reason: DECISION_REASON_ID,
        });
        if (marked.length === 0) {
          this.toast.error(refusalReason(err) ?? this.translate.instant('BILLING.REFUNDS.FAILED'));
        }
      },
    });
  }

  downloadReceipt(r: InvoiceRefund): void {
    this.billing.getRefundReceiptPdf(r.invoiceId, r.id).subscribe({
      next: (res) => saveResponse(res, `refund-${this.invoice().invoiceNumber}.pdf`),
      error: (err: unknown) =>
        this.toast.error(refusalReason(err) ?? this.translate.instant('BILLING.PDF_FAILED')),
    });
  }

  private stepObserver(r: InvoiceRefund, successKey: string) {
    return {
      next: () => {
        this.busy.set(false);
        this.toast.success(this.translate.instant(successKey));
        this.afterStep(r.invoiceId);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.toast.error(refusalReason(err) ?? this.translate.instant('BILLING.REFUNDS.FAILED'));
        // Someone else may have moved it meanwhile: show where it stands.
        this.load(r.invoiceId);
      },
    };
  }

  private afterStep(invoiceId: string): void {
    this.load(invoiceId);
    this.changed.emit();
  }
}
