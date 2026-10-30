import { invoiceTone, InvoiceTone } from '../../shared/invoice-tone';
import { Component, OnInit, inject, signal, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  PatientPortalService,
  PortalInvoice,
  PortalInvoiceDetail,
  PortalInvoicePayment,
  PatientPaymentRequest,
} from '../../services/patient-portal.service';
import { saveResponse } from '../../shared/download-file';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { CfaPipe } from '../../shared/pipes/cfa.pipe';
import { DialogA11yDirective } from '../../shared/a11y/dialog-a11y.directive';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { formatCfa } from '../../shared/i18n/app-locale';
import { cfaAmountMessageKey, cfaAmountProblem } from '../../shared/cfa-amount';

@Component({
  selector: 'app-my-billing',
  standalone: true,
  imports: [
    ServerTimePipe,
    CommonModule,
    DatePipe,
    FormsModule,
    EnumLabelPipe,
    TranslateModule,
    CfaPipe,
    DialogA11yDirective,
  ],
  templateUrl: './my-billing.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrls: ['./my-billing.component.scss', '../patient-portal-pages.scss'],
})
export class MyBillingComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  private readonly translate = inject(TranslateService);
  invoices = signal<PortalInvoice[]>([]);
  loading = signal(true);
  totalBalance = signal(0);

  /**
   * QA D982: the card opens on the invoice itself: its lines, every payment
   * with its state, and the invoice and receipt PDFs.
   */
  openDetailId = signal<string | null>(null);
  detail = signal<PortalInvoiceDetail | null>(null);
  detailLoading = signal(false);
  detailError = signal(false);
  /** A failed PDF download, shown beside the detail instead of replacing it. */
  downloadError = signal(false);

  toggleDetail(inv: PortalInvoice): void {
    if (this.openDetailId() === inv.id) {
      this.openDetailId.set(null);
      this.detail.set(null);
      return;
    }
    this.openDetailId.set(inv.id);
    this.detail.set(null);
    this.detailError.set(false);
    this.detailLoading.set(true);
    this.portal.getMyInvoiceDetail(inv.id).subscribe({
      next: (d) => {
        if (this.openDetailId() !== inv.id) return;
        this.detail.set(d);
        this.detailLoading.set(false);
      },
      error: () => {
        if (this.openDetailId() !== inv.id) return;
        this.detailError.set(true);
        this.detailLoading.set(false);
      },
    });
  }

  downloadInvoice(inv: PortalInvoice): void {
    this.portal.getMyInvoicePdf(inv.id).subscribe({
      next: (res) => {
        this.downloadError.set(false);
        saveResponse(res, `invoice-${inv.invoiceNumber}.pdf`);
      },
      error: () => this.downloadError.set(true),
    });
  }

  downloadReceipt(inv: PortalInvoice, p: PortalInvoicePayment): void {
    this.portal.getMyReceiptPdf(inv.id, p.id).subscribe({
      next: (res) => {
        this.downloadError.set(false);
        saveResponse(
          res,
          `receipt-${inv.invoiceNumber}-${(p.paymentDate ?? '').substring(0, 10)}.pdf`,
        );
      },
      error: () => this.downloadError.set(true),
    });
  }

  // Payment dialog state
  payingInvoice = signal<PortalInvoice | null>(null);
  payAmount = 0;
  payMethod = 'CARD';
  payReference = '';
  payProcessing = signal(false);
  payError = signal('');
  paySuccess = signal('');

  ngOnInit() {
    this.loadInvoices();
  }

  private loadInvoices() {
    this.portal.getMyInvoices().subscribe({
      next: (inv) => {
        this.invoices.set(inv);
        // QA D982: an open detail follows a payment just declared.
        const open = inv.find((i) => i.id === this.openDetailId());
        if (open) {
          this.openDetailId.set(null);
          this.toggleDetail(open);
        }
        this.totalBalance.set(
          inv.filter((i) => this.isPayable(i)).reduce((sum, i) => sum + (i.balance ?? 0), 0),
        );
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  /** QA D1071: the card's icon and tone, from the invoice's status. */
  toneOf(inv: PortalInvoice): InvoiceTone {
    return invoiceTone(inv, this.isPayable(inv));
  }

  /** QA D202: only an issued, open invoice with money left on it is owed or payable. */
  isPayable(inv: PortalInvoice): boolean {
    return (
      (inv.balance ?? 0) > 0 &&
      inv.status !== 'DRAFT' &&
      inv.status !== 'CANCELLED' &&
      inv.status !== 'PAID'
    );
  }

  /** QA D421: what is already declared on this invoice and awaiting confirmation. */
  pendingOf(inv: PortalInvoice): number {
    return Math.max(inv.pending ?? 0, 0);
  }

  /** QA D421: what the patient may still declare: the balance less what is pending. */
  declarable(inv: PortalInvoice): number {
    return Math.max((inv.balance ?? 0) - this.pendingOf(inv), 0);
  }

  /**
   * QA D814: "Pay now" only while something is left to declare. With the
   * whole balance declared and awaiting the cashier, the dialog could only
   * offer 0 F CFA.
   */
  canDeclare(inv: PortalInvoice): boolean {
    return this.isPayable(inv) && this.declarable(inv) > 0;
  }

  openPayDialog(inv: PortalInvoice) {
    if (!this.canDeclare(inv)) return;
    this.payingInvoice.set(inv);
    // QA D421: prefilled with what is left to declare, not the full balance
    // (which the server refused whenever a declaration was pending).
    this.payAmount = this.declarable(inv);
    this.payMethod = 'CARD';
    this.payReference = '';
    this.payError.set('');
    this.paySuccess.set('');
  }

  closePayDialog() {
    this.payingInvoice.set(null);
    this.payError.set('');
    this.paySuccess.set('');
  }

  submitPayment() {
    const inv = this.payingInvoice();
    if (!inv) return;

    // QA D184 / D1089: an empty amount is answered (it used to do nothing),
    // then whole francs (francs CFA have no cents).
    const amountProblem = cfaAmountProblem(this.payAmount);
    if (amountProblem) {
      this.payError.set(this.translate.instant(cfaAmountMessageKey(amountProblem)));
      return;
    }

    if (this.payAmount > inv.balance) {
      this.payError.set(this.translate.instant('PORTAL.BILLING.AMOUNT_EXCEEDS'));
      return;
    }
    // QA D421: refused in the figures the dialog shows.
    if (this.payAmount > this.declarable(inv)) {
      this.payError.set(
        this.translate.instant('PORTAL.BILLING.AMOUNT_EXCEEDS_PENDING', {
          max: formatCfa(this.declarable(inv)),
          pending: formatCfa(this.pendingOf(inv)),
        }),
      );
      return;
    }

    // QA D433: only cash is declared without a reference staff can check.
    if (this.payMethod !== 'CASH' && !this.payReference.trim()) {
      this.payError.set(this.translate.instant('PORTAL.BILLING.REFERENCE_REQUIRED'));
      return;
    }

    this.payProcessing.set(true);
    this.payError.set('');

    const req: PatientPaymentRequest = {
      amount: this.payAmount,
      paymentMethod: this.payMethod,
      transactionReference: this.payReference || undefined,
    };

    this.portal.payInvoice(inv.id, req).subscribe({
      next: () => {
        this.payProcessing.set(false);
        this.paySuccess.set(
          // QA D140: a declaration is not a settlement; staff confirm it.
          this.translate.instant('PORTAL.BILLING.PAYMENT_PENDING', {
            // QA D815: "500 F CFA", as every other amount on the page.
            amount: formatCfa(this.payAmount),
          }),
        );
        this.loadInvoices();
      },
      error: (err) => {
        this.payProcessing.set(false);
        const msg =
          err?.error?.message ||
          err?.error?.error ||
          this.translate.instant('PORTAL.BILLING.PAYMENT_FAILED');
        this.payError.set(msg);
      },
    });
  }
}
