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
import {
  MOBILE_MONEY_OPERATORS,
  containsCardNumber,
  isMobileMoney,
  mobileMoneyOperatorLabelKey,
} from '../../shared/mobile-money-operator';

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

/** The hand-over dialog's fields. */
const PAY_OPERATOR_ID = 'refund-pay-operator';
const PAY_REFERENCE_ID = 'refund-pay-reference';
const PAY_FIELD_IDS = [PAY_OPERATOR_ID, PAY_REFERENCE_ID];

/**
 * A fresh idempotency key for one refund request (kept across retries of the same dialog): a v4 UUID, as the server
 * reads it. Where `randomUUID` is missing (an older browser, a non-secure origin), the 16 random bytes come from
 * `getRandomValues` and are given the v4 version and variant bits (PR 8 review).
 */
export function newRequestKey(): string {
  const cryptoApi = globalThis.crypto;
  if (typeof cryptoApi?.randomUUID === 'function') return cryptoApi.randomUUID();
  const bytes = new Uint8Array(16);
  cryptoApi.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
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
  readonly needsOperator = computed(() => isMobileMoney(this.method()));
  /** Billing plan PR 9: the stored operator's label ("Opérateur non précisé" when none). */
  readonly operatorLabelKey = mobileMoneyOperatorLabelKey;
  readonly needsReference = computed(() => REFERENCE_REQUIRED.includes(this.method()));

  /* ── the hand-over dialog (PR 8 review: the reference is known when the money is sent) ── */
  readonly handingOver = signal<InvoiceRefund | null>(null);
  readonly payOperator = signal('');
  readonly payReference = signal('');
  readonly payNeedsOperator = computed(() => isMobileMoney(this.handingOver()?.method));
  readonly payNeedsReference = computed(() =>
    REFERENCE_REQUIRED.includes(this.handingOver()?.method ?? ''),
  );

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
    // Owner decision D-15: never a card number, said before anything is sent (the server refuses it too).
    for (const [id, value] of [
      [REFERENCE_ID, this.reference()],
      [REASON_ID, this.reason()],
    ] as const) {
      if (containsCardNumber(value)) {
        refused.push({ id, message: this.translate.instant('MOBILE_MONEY.CARD_NUMBER_REFUSED') });
      }
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
        mobileMoneyOperator: this.needsOperator() && this.operator() ? this.operator() : null,
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

  /**
   * A cash (or cheque, other) refund is handed over at once. Mobile money and bank transfer are sent now, so the
   * dialog asks for the transaction reference (and the operator for mobile money), as the server requires.
   */
  handOver(r: InvoiceRefund): void {
    if (this.busy()) return;
    if (REFERENCE_REQUIRED.includes(r.method)) {
      this.payOperator.set(r.mobileMoneyOperator ?? '');
      this.payReference.set(r.reference ?? '');
      this.handingOver.set(r);
      return;
    }
    this.busy.set(true);
    this.billing
      .payRefund(r.invoiceId, r.id)
      .subscribe(this.stepObserver(r, 'BILLING.REFUNDS.PAID_TOAST'));
  }

  closeHandOver(): void {
    this.handingOver.set(null);
  }

  submitHandOver(): void {
    const r = this.handingOver();
    if (!r || this.busy()) return;
    const refused: { id: string; message: string }[] = [];
    if (this.payNeedsOperator() && !this.payOperator()) {
      refused.push({
        id: PAY_OPERATOR_ID,
        message: this.translate.instant('MOBILE_MONEY.OPERATOR_REQUIRED'),
      });
    }
    const reference = this.payReference().trim();
    if (this.payNeedsReference() && !reference) {
      refused.push({
        id: PAY_REFERENCE_ID,
        message: this.translate.instant('BILLING.REFUNDS.REFERENCE_REQUIRED'),
      });
    } else if (containsCardNumber(reference)) {
      refused.push({
        id: PAY_REFERENCE_ID,
        message: this.translate.instant('MOBILE_MONEY.CARD_NUMBER_REFUSED'),
      });
    }
    if (refused.length > 0) {
      refuseFields(this.host.nativeElement, refused, PAY_FIELD_IDS);
      this.toast.error(joinReasons(refused.map((f) => f.message)) ?? '');
      return;
    }
    this.busy.set(true);
    this.billing
      .payRefund(r.invoiceId, r.id, {
        reference: reference || null,
        mobileMoneyOperator: this.payNeedsOperator() ? this.payOperator() : null,
      })
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.handingOver.set(null);
          this.toast.success(this.translate.instant('BILLING.REFUNDS.PAID_TOAST'));
          this.afterStep(r.invoiceId);
        },
        error: (err: unknown) => {
          this.busy.set(false);
          const marked = markServerFieldErrors(this.host.nativeElement, err, {
            mobileMoneyOperator: PAY_OPERATOR_ID,
            reference: PAY_REFERENCE_ID,
          });
          if (marked.length === 0) {
            this.toast.error(
              refusalReason(err) ?? this.translate.instant('BILLING.REFUNDS.FAILED'),
            );
          }
        },
      });
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
