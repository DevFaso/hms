import { Component, Input, Output, EventEmitter, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { ReceptionQueueItem, ReceptionService } from '../reception.service';
import { ToastService } from '../../core/toast.service';

interface OpenInvoice {
  id: string;
  invoiceNumber: string;
  totalAmount: number;
  amountPaid: number;
  balanceDue: number;
  status: string;
}

@Component({
  selector: 'app-payment-pending-panel',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './payment-pending-panel.component.html',
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

  payingItem = signal<ReceptionQueueItem | null>(null);
  openInvoices = signal<OpenInvoice[]>([]);
  selectedInvoiceId = signal<string | null>(null);
  payAmount = signal<number | null>(null);
  payMethod = signal<string>('CASH');
  paying = signal(false);
  loadingInvoices = signal(false);

  openPayDialog(item: ReceptionQueueItem): void {
    this.payingItem.set(item);
    this.payAmount.set(null);
    this.payMethod.set('CASH');
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

  closePayDialog(): void {
    this.payingItem.set(null);
  }

  submitPayment(): void {
    const invoiceId = this.selectedInvoiceId();
    const amount = this.payAmount();
    const method = this.payMethod();
    if (!invoiceId || !amount || amount <= 0) return;

    this.paying.set(true);
    this.receptionService.recordPayment(invoiceId, amount, method).subscribe({
      next: () => {
        this.paying.set(false);
        this.closePayDialog();
        this.paymentCollected.emit();
        this.toast.success(this.translate.instant('RECEPTION.PAYMENT_RECORDED'));
      },
      error: (err) => {
        this.paying.set(false);
        const msg = err?.error?.message ?? this.translate.instant('RECEPTION.PAYMENT_FAILED');
        this.toast.error(msg);
      },
    });
  }
}
