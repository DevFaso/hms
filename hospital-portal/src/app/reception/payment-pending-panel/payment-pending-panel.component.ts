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
import { refuseFields } from '../../shared/forms/invalid-fields';
import { cfaAmountMessageKey, cfaAmountProblem } from '../../shared/cfa-amount';

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
  imports: [CommonModule, FormsModule, TranslateModule, CfaPipe, DialogFocusDirective],
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

  openPayDialog(item: ReceptionQueueItem): void {
    this.payingItem.set(item);
    this.payAmount.set(null);
    this.payMethod.set('CASH');
    this.payReference.set('');
    this.selectedInvoiceId.set(null);
    this.openInvoices.set([]);
    this.loadInvoices(item.patientId);
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
          if (open.length === 1) {
            this.selectedInvoiceId.set(open[0].id);
            this.payAmount.set(
              open[0].balanceDue ?? open[0].totalAmount - (open[0].amountPaid ?? 0),
            );
          }
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
    const amountProblem = cfaAmountProblem(amount);
    const missing = [
      !invoiceId ? 'invSelect' : null,
      amountProblem ? 'payAmt' : null,
      this.needsReference() && !reference ? 'payRef' : null,
    ].filter((id): id is string => id !== null);
    if (missing.length > 0 || !invoiceId || !amount) {
      refuseFields(
        this.host.nativeElement,
        missing,
        ['invSelect', 'payAmt', 'payRef'],
        missing[0] === 'payAmt' && amountProblem
          ? this.translate.instant(cfaAmountMessageKey(amountProblem))
          : this.translate.instant('COMMON.FIELD_REQUIRED'),
      );
      return;
    }

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
          error: () => this.toast.error(this.translate.instant('RECEPTION.RECEIPT_FAILED')),
        });
      },
      error: () => this.toast.error(this.translate.instant('RECEPTION.RECEIPT_FAILED')),
    });
  }
}

/** The payment recorded last (its creation time, else its payment date). */
export function newestPayment(payments: PaymentRow[]): PaymentRow | null {
  const when = (p: PaymentRow) => p.createdAt ?? p.paymentDate ?? '';
  return [...payments].sort((a, b) => when(b).localeCompare(when(a)))[0] ?? null;
}
