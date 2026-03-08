import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe, CurrencyPipe, TitleCasePipe } from '@angular/common';
import { PatientPortalService, PortalInvoice } from '../services/patient-portal.service';

@Component({
  selector: 'app-my-billing',
  standalone: true,
  imports: [CommonModule, DatePipe, CurrencyPipe, TitleCasePipe],
  template: `
    <div class="portal-page">
      <div class="portal-page-header">
        <h1>
          <span class="material-symbols-outlined">receipt_long</span>
          Billing & Payments
        </h1>
      </div>

      @if (loading()) {
        <div class="portal-loading">
          <div class="portal-spinner"></div>
          <p>Loading billing information...</p>
        </div>
      } @else if (invoices().length === 0) {
        <div class="portal-empty">
          <span class="material-symbols-outlined">payments</span>
          <h3>No billing statements</h3>
          <p>You have no outstanding or past billing statements.</p>
        </div>
      } @else {
        @if (totalBalance() > 0) {
          <div class="billing-summary-card">
            <div class="bsc-icon">
              <span class="material-symbols-outlined">account_balance_wallet</span>
            </div>
            <div class="bsc-body">
              <span class="bsc-label">Total Balance Due</span>
              <span class="bsc-amount">{{ totalBalance() | currency }}</span>
            </div>
          </div>
        }

        <section class="portal-section">
          <h2 class="portal-section-title">Billing Statements</h2>
          <div class="portal-list">
            @for (inv of invoices(); track inv.id) {
              <div class="portal-list-item">
                <div
                  class="pli-icon"
                  [style.background]="inv.balance > 0 ? '#fef3c7' : '#d1fae5'"
                  [style.color]="inv.balance > 0 ? '#d97706' : '#059669'"
                >
                  <span class="material-symbols-outlined">{{
                    inv.balance > 0 ? 'pending' : 'check_circle'
                  }}</span>
                </div>
                <div class="pli-body">
                  <span class="pli-title">{{
                    inv.description || inv.facility || 'Medical Services'
                  }}</span>
                  <span class="pli-sub"
                    >Invoice #{{ inv.invoiceNumber }} · {{ inv.date | date: 'MMM d, yyyy' }}</span
                  >
                  <span class="pli-meta">Due {{ inv.dueDate | date: 'MMM d, yyyy' }}</span>
                </div>
                <div class="pli-end">
                  <span class="pli-amount" [class.has-balance]="inv.balance > 0">{{
                    inv.balance | currency
                  }}</span>
                  <span class="pli-status" [attr.data-status]="inv.status">{{
                    inv.status | titlecase
                  }}</span>
                </div>
              </div>
            }
          </div>
        </section>
      }
    </div>
  `,
  styles: [
    `
      .billing-summary-card {
        display: flex;
        align-items: center;
        gap: 16px;
        padding: 20px 24px;
        background: linear-gradient(135deg, #fef3c7 0%, #fff7ed 100%);
        border: 1px solid #fde68a;
        border-radius: 14px;
        margin-bottom: 20px;
      }
      .bsc-icon {
        width: 48px;
        height: 48px;
        border-radius: 12px;
        background: #d97706;
        color: #fff;
        display: flex;
        align-items: center;
        justify-content: center;
      }
      .bsc-body {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }
      .bsc-label {
        font-size: 13px;
        font-weight: 600;
        color: #92400e;
      }
      .bsc-amount {
        font-size: 28px;
        font-weight: 800;
        color: #78350f;
      }
      .pli-end {
        display: flex;
        flex-direction: column;
        align-items: flex-end;
        gap: 4px;
        flex-shrink: 0;
      }
      .pli-amount {
        font-size: 15px;
        font-weight: 700;
        color: #1e293b;
      }
      .pli-amount.has-balance {
        color: #d97706;
      }
      .pli-status {
        font-size: 11px;
        font-weight: 600;
        padding: 2px 8px;
        border-radius: 12px;
        background: #f1f5f9;
        color: #64748b;
      }
      .pli-status[data-status='PAID'] {
        background: #d1fae5;
        color: #059669;
      }
      .pli-status[data-status='PENDING'],
      .pli-status[data-status='OVERDUE'] {
        background: #fef3c7;
        color: #d97706;
      }
    `,
  ],
  styleUrl: './patient-portal-pages.scss',
})
export class MyBillingComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  invoices = signal<PortalInvoice[]>([]);
  loading = signal(true);
  totalBalance = signal(0);

  ngOnInit() {
    this.portal.getMyInvoices().subscribe({
      next: (inv) => {
        this.invoices.set(inv);
        this.totalBalance.set(inv.reduce((sum, i) => sum + (i.balance ?? 0), 0));
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }
}
