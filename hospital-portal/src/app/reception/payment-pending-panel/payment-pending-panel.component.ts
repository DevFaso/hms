import { refusalReason } from '../../shared/refusal-reason';
import {
  Component,
  Input,
  Output,
  EventEmitter,
  computed,
  inject,
  signal,
  ChangeDetectionStrategy,
  ElementRef,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { CfaPipe } from '../../shared/pipes/cfa.pipe';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { ReceptionQueueItem, ReceptionService } from '../reception.service';
import { ToastService } from '../../core/toast.service';
import { DialogFocusDirective } from '../../shared/a11y/dialog-focus.directive';
import { DESK_PAYMENT_METHODS, deskMethodNeedsReference } from '../desk-payment-methods';
import { BillingService, PaymentRow } from '../../services/billing.service';
import { saveResponse } from '../../shared/download-file';
import { FieldRefusal, refuseFields } from '../../shared/forms/invalid-fields';
import { amountStillDue, pendingDeclared } from '../../shared/payment-prefill';
import { formatCfa } from '../../shared/i18n/app-locale';

/** The fields the collect dialog may refuse. */
const PAY_FIELDS = ['invSelect', 'payAmt', 'payRef'];
import {
  CFA_AMOUNT_REQUIRED_KEY,
  cfaAmountMessageKey,
  cfaAmountProblem,
} from '../../shared/cfa-amount';
import { DashPipe } from '../../shared/pipes/dash.pipe';

interface OpenInvoice {
  id: string;
  invoiceNumber: string;
  totalAmount: number;
  amountPaid: number;
  balanceDue: number;
  status: string;
  currency?: string | null;
}

@Component({
  selector: 'app-payment-pending-panel',
  standalone: true,
  imports: [DashPipe, CommonModule, FormsModule, TranslateModule, CfaPipe, DialogFocusDirective],
  templateUrl: './payment-pending-panel.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './payment-pending-panel.component.scss',
})
export class PaymentPendingPanelComponent {
  @Input() items: ReceptionQueueItem[] = [];
  @Output() patientClicked = new EventEmitter<string>();
  @Output() paymentCollected = new EventEmitter<void>();

  private readonly receptionService = inject(ReceptionService);
  private readonly http = inject(HttpClient);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly billing = inject(BillingService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  payingItem = signal<ReceptionQueueItem | null>(null);
  openInvoices = signal<OpenInvoice[]>([]);
  selectedInvoiceId = signal<string | null>(null);
  payAmount = signal<number | null>(null);
  payMethod = signal<string>('CASH');
  /** QA D948: the operator or policy reference of a Mobile Money or insurance payment. */
  payReference = signal('');
  readonly methods = DESK_PAYMENT_METHODS;
  readonly needsReference = computed(() => deskMethodNeedsReference(this.payMethod()));
  /** The selected invoice's currency (francs CFA unless the invoice says otherwise). */
  readonly selectedCurrency = computed(
    () => this.openInvoices().find((inv) => inv.id === this.selectedInvoiceId())?.currency ?? null,
  );
  paying = signal(false);
  loadingInvoices = signal(false);
  /**
   * QA D1321 (round 29): what the patient declared on the selected invoice and
   * staff have not confirmed yet. The dialog says it, prefills only what is
   * still due, and refuses more.
   */
  pending = signal(0);

  openPayDialog(item: ReceptionQueueItem): void {
    this.payingItem.set(item);
    this.payAmount.set(null);
    this.payMethod.set('CASH');
    this.payReference.set('');
    this.selectedInvoiceId.set(null);
    this.pending.set(0);
    this.openInvoices.set([]);
    this.loadInvoices(item.patientId);
  }

  /** An invoice picked in the select (or the only one): prefill what is still due on it. */
  selectInvoice(invoiceId: string | null): void {
    this.selectedInvoiceId.set(invoiceId);
    this.pending.set(0);
    const inv = this.openInvoices().find((i) => i.id === invoiceId);
    this.payAmount.set(inv ? this.balanceOf(inv) : null);
    if (!inv) return;
    this.billing.listPayments(inv.id).subscribe({
      next: (rows) => {
        if (this.selectedInvoiceId() !== inv.id) return;
        const pending = pendingDeclared(rows);
        this.pending.set(pending);
        // Only the untouched prefill moves: an amount the reader typed stays.
        if (pending > 0 && this.payAmount() === this.balanceOf(inv)) {
          this.payAmount.set(amountStillDue(this.balanceOf(inv), pending));
        }
      },
      error: () => this.pending.set(0),
    });
  }

  /** The most the desk may take on the selected invoice: its balance less what is pending. */
  maxCollectable(): number | null {
    const inv = this.openInvoices().find((i) => i.id === this.selectedInvoiceId());
    return inv ? Math.max(this.balanceOf(inv) - this.pending(), 0) : null;
  }

  private loadInvoices(patientId: string): void {
    this.loadingInvoices.set(true);
    const params = new HttpParams().set('size', '20');
    this.http
      .get<{ content: OpenInvoice[] }>(`/billing-invoices/patient/${patientId}`, { params })
      .subscribe({
        next: (res) => {
          const open = (res.content ?? []).filter(
            (inv) =>
              inv.status !== 'PAID' &&
              inv.status !== 'CANCELLED' &&
              inv.status !== 'DRAFT' &&
              (inv.balanceDue ?? inv.totalAmount - (inv.amountPaid ?? 0)) > 0,
          );
          this.openInvoices.set(open);
          if (open.length === 1) this.selectInvoice(open[0].id);
          this.loadingInvoices.set(false);
        },
        error: () => {
          this.toast.error(this.translate.instant('RECEPTION.LOAD_INVOICES_FAILED'));
          this.loadingInvoices.set(false);
        },
      });
  }

  /** The amount still due on an invoice. */
  balanceOf(inv: OpenInvoice): number {
    return inv.balanceDue ?? inv.totalAmount - (inv.amountPaid ?? 0);
  }

  closePayDialog(): void {
    this.payingItem.set(null);
  }

  submitPayment(): void {
    const invoiceId = this.selectedInvoiceId();
    const amount = this.payAmount();
    const method = this.payMethod();
    const reference = this.payReference().trim();
    // QA D1089: the button is never dead; an empty field says what is missing.
    // QA D1296 (round 28): every refused field in one press, each with its
    // own reason (the amount's reason was printed under every field, so the
    // invoice was refused only once the amount was valid).
    const amountProblem = cfaAmountProblem(amount);
    const required = this.translate.instant('COMMON.FIELD_REQUIRED');
    const refusals: FieldRefusal[] = [];
    if (!invoiceId) refusals.push({ id: 'invSelect', message: required });
    const max = this.maxCollectable();
    if (amountProblem) {
      refusals.push({
        id: 'payAmt',
        message: this.translate.instant(
          cfaAmountMessageKey(amountProblem, CFA_AMOUNT_REQUIRED_KEY),
        ),
      });
    } else if (max !== null && amount !== null && amount > max) {
      // QA D1321: more than is still due once the patient's declaration is
      // left out — the server refuses it; say it on the field first.
      const currency = this.selectedCurrency();
      refusals.push({
        id: 'payAmt',
        message:
          this.pending() > 0
            ? this.translate.instant('RECEPTION.PAYMENT_EXCEEDS_WITH_PENDING', {
                max: formatCfa(max, currency),
                pending: formatCfa(this.pending(), currency),
              })
            : this.translate.instant('BILLING.PAYMENT_EXCEEDS_BALANCE'),
      });
    }
    if (this.needsReference() && !reference) refusals.push({ id: 'payRef', message: required });
    if (refusals.length > 0 || !invoiceId || !amount) {
      refuseFields(this.host.nativeElement, refusals, PAY_FIELDS);
      return;
    }
    refuseFields(this.host.nativeElement, [], PAY_FIELDS);

    this.paying.set(true);
    this.receptionService
      .recordPayment(invoiceId, amount, method, reference || undefined)
      .subscribe({
        next: () => {
          this.paying.set(false);
          this.closePayDialog();
          this.paymentCollected.emit();
          this.toast.success(this.translate.instant('RECEPTION.PAYMENT_RECORDED'));
          this.downloadReceipt(invoiceId);
        },
        error: (err) => {
          this.paying.set(false);
          const msg = refusalReason(err) ?? this.translate.instant('RECEPTION.PAYMENT_FAILED');
          this.toast.error(msg);
        },
      });
  }

  /**
   * QA D1022: the receipt of the payment just taken, handed to the patient as
   * at check-in (D975). The payment endpoint answers with the invoice, so the
   * newest payment on it is the one recorded here.
   */
  private downloadReceipt(invoiceId: string): void {
    this.billing.listPayments(invoiceId).subscribe({
      next: (payments) => {
        const latest = newestPayment(payments);
        if (!latest) return;
        this.billing.getReceiptPdf(invoiceId, latest.id).subscribe({
          next: (res) => saveResponse(res, 'recu.pdf'),
          error: (err: unknown) =>
            this.toast.error(
              refusalReason(err) ?? this.translate.instant('RECEPTION.RECEIPT_FAILED'),
            ),
        });
      },
      error: (err: unknown) =>
        this.toast.error(refusalReason(err) ?? this.translate.instant('RECEPTION.RECEIPT_FAILED')),
    });
  }
}

/** The payment recorded last (its creation time, else its payment date). */
export function newestPayment(payments: PaymentRow[]): PaymentRow | null {
  const when = (p: PaymentRow) => p.createdAt ?? p.paymentDate ?? '';
  return [...payments].sort((a, b) => when(b).localeCompare(when(a)))[0] ?? null;
}
