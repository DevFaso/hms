import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe, CurrencyPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import {
  PatientPortalService,
  PortalInvoice,
  PatientPaymentRequest,
} from '../../services/patient-portal.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';

@Component({
  selector: 'app-my-billing',
  standalone: true,
  imports: [CommonModule, DatePipe, CurrencyPipe, FormsModule, EnumLabelPipe, TranslateModule],
  templateUrl: './my-billing.component.html',
  styleUrls: ['./my-billing.component.scss', '../patient-portal-pages.scss'],
})
export class MyBillingComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  invoices = signal<PortalInvoice[]>([]);
  loading = signal(true);
  totalBalance = signal(0);

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
        this.totalBalance.set(inv.reduce((sum, i) => sum + (i.balance ?? 0), 0));
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  openPayDialog(inv: PortalInvoice) {
    this.payingInvoice.set(inv);
    this.payAmount = inv.balance;
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
    if (!inv || !this.payAmount || this.payAmount <= 0) return;

    if (this.payAmount > inv.balance) {
      this.payError.set('Amount cannot exceed balance due.');
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
        this.paySuccess.set('Payment of ' + this.payAmount.toFixed(2) + ' recorded successfully!');
        this.loadInvoices();
      },
      error: (err) => {
        this.payProcessing.set(false);
        const msg = err?.error?.message || err?.error?.error || 'Payment failed. Please try again.';
        this.payError.set(msg);
      },
    });
  }
}
