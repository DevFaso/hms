import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { BillingService, BillingInvoiceResponse } from '../services/billing.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-billing',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './billing.html',
  styleUrl: './billing.scss',
})
export class BillingComponent implements OnInit {
  private readonly billingService = inject(BillingService);
  private readonly toast = inject(ToastService);

  invoices = signal<BillingInvoiceResponse[]>([]);
  filtered = signal<BillingInvoiceResponse[]>([]);
  searchTerm = '';
  loading = signal(true);
  activeTab = signal<'all' | 'pending' | 'paid' | 'overdue'>('all');
  selectedInvoice = signal<BillingInvoiceResponse | null>(null);

  stats = signal({ total: 0, paid: 0, pending: 0, overdue: 0, totalRevenue: 0, outstanding: 0 });

  ngOnInit(): void {
    this.loadInvoices();
  }

  loadInvoices(): void {
    this.loading.set(true);
    this.billingService.listInvoices().subscribe({
      next: (res) => {
        const list = Array.isArray(res) ? res : [];
        this.invoices.set(list);
        this.computeStats(list);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load billing data');
        this.loading.set(false);
      },
    });
  }

  private computeStats(list: BillingInvoiceResponse[]): void {
    this.stats.set({
      total: list.length,
      paid: list.filter((i) => i.status === 'PAID').length,
      pending: list.filter((i) => i.status === 'PENDING' || i.status === 'DRAFT').length,
      overdue: list.filter((i) => i.status === 'OVERDUE').length,
      totalRevenue: list.reduce((sum, i) => sum + (i.amountPaid ?? 0), 0),
      outstanding: list.reduce((sum, i) => sum + (i.balanceDue ?? 0), 0),
    });
  }

  setTab(tab: 'all' | 'pending' | 'paid' | 'overdue'): void {
    this.activeTab.set(tab);
    this.applyFilter();
  }

  applyFilter(): void {
    let list = this.invoices();
    const tab = this.activeTab();
    if (tab === 'pending') {
      list = list.filter((i) => i.status === 'PENDING' || i.status === 'DRAFT');
    } else if (tab === 'paid') {
      list = list.filter((i) => i.status === 'PAID');
    } else if (tab === 'overdue') {
      list = list.filter((i) => i.status === 'OVERDUE');
    }
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (inv) =>
          (inv.invoiceNumber ?? '').toLowerCase().includes(term) ||
          (inv.patientFullName ?? '').toLowerCase().includes(term) ||
          (inv.status ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
  }

  viewInvoice(inv: BillingInvoiceResponse): void {
    this.selectedInvoice.set(inv);
  }

  closeDetail(): void {
    this.selectedInvoice.set(null);
  }

  downloadPdf(id: string): void {
    this.billingService.getInvoicePdf(id).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `invoice-${id}.pdf`;
        a.click();
        URL.revokeObjectURL(url);
      },
      error: () => this.toast.error('Failed to download PDF'),
    });
  }

  emailInvoice(inv: BillingInvoiceResponse): void {
    const to = inv.patientEmail ? [inv.patientEmail] : [];
    if (!to.length) {
      this.toast.error('No patient email available');
      return;
    }
    this.billingService.emailInvoice(inv.id, { to, attachPdf: true }).subscribe({
      next: () => this.toast.success('Invoice emailed successfully'),
      error: () => this.toast.error('Failed to email invoice'),
    });
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'PAID':
        return 'status-badge status-paid';
      case 'PENDING':
      case 'DRAFT':
        return 'status-badge status-pending';
      case 'OVERDUE':
        return 'status-badge status-overdue';
      case 'CANCELLED':
        return 'status-badge status-cancelled';
      case 'PARTIAL':
        return 'status-badge status-partial';
      case 'REFUNDED':
        return 'status-badge status-refunded';
      default:
        return 'status-badge';
    }
  }

  formatCurrency(amount: number): string {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'XOF',
      maximumFractionDigits: 0,
    }).format(amount ?? 0);
  }
}
