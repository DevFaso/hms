import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe, CurrencyPipe, TitleCasePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  PatientPortalService,
  PortalInvoice,
  PatientPaymentRequest,
} from '../services/patient-portal.service';

@Component({
  selector: 'app-my-billing',
  standalone: true,
  imports: [CommonModule, DatePipe, CurrencyPipe, TitleCasePipe, FormsModule],
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
                  @if (inv.balance > 0 && inv.status !== 'DRAFT') {
                    <button class="pay-btn" (click)="openPayDialog(inv)">
                      <span class="material-symbols-outlined">payment</span> Pay Now
                    </button>
                  }
                </div>
              </div>
            }
          </div>
        </section>
      }

      <!-- Payment Dialog -->
      @if (payingInvoice()) {
        <div
          class="pay-overlay"
          role="dialog"
          aria-modal="true"
          (click)="closePayDialog()"
          (keydown.escape)="closePayDialog()"
          tabindex="-1"
        >
          <div
            class="pay-dialog"
            role="document"
            (click)="$event.stopPropagation()"
            (keydown.escape)="closePayDialog()"
          >
            <div class="pay-dialog-header">
              <h3>
                <span class="material-symbols-outlined">payment</span>
                Make a Payment
              </h3>
              <button class="pay-dialog-close" (click)="closePayDialog()">
                <span class="material-symbols-outlined">close</span>
              </button>
            </div>

            <div class="pay-dialog-body">
              <div class="pay-invoice-info">
                <span class="pay-label">Invoice</span>
                <span class="pay-value">#{{ payingInvoice()!.invoiceNumber }}</span>
              </div>
              <div class="pay-invoice-info">
                <span class="pay-label">Balance Due</span>
                <span class="pay-value pay-balance">{{ payingInvoice()!.balance | currency }}</span>
              </div>

              @if (payError()) {
                <div class="pay-error">{{ payError() }}</div>
              }
              @if (paySuccess()) {
                <div class="pay-success">{{ paySuccess() }}</div>
              }

              @if (!paySuccess()) {
                <div class="pay-field">
                  <label for="payAmount">Amount</label>
                  <input
                    id="payAmount"
                    type="number"
                    [min]="0.01"
                    [max]="payingInvoice()!.balance"
                    step="0.01"
                    [(ngModel)]="payAmount"
                    placeholder="Enter amount"
                  />
                </div>

                <div class="pay-field">
                  <label for="payMethod">Payment Method</label>
                  <select id="payMethod" [(ngModel)]="payMethod">
                    <option value="CARD">Credit / Debit Card</option>
                    <option value="BANK_TRANSFER">Bank Transfer</option>
                    <option value="MOBILE_MONEY">Mobile Money</option>
                    <option value="CASH">Cash</option>
                  </select>
                </div>

                <div class="pay-field">
                  <label for="payRef">Reference / Transaction ID (optional)</label>
                  <input
                    id="payRef"
                    type="text"
                    [(ngModel)]="payReference"
                    placeholder="e.g. TXN-123456"
                    maxlength="500"
                  />
                </div>

                <div class="pay-actions">
                  <button
                    class="pay-cancel-btn"
                    (click)="closePayDialog()"
                    [disabled]="payProcessing()"
                  >
                    Cancel
                  </button>
                  <button
                    class="pay-submit-btn"
                    (click)="submitPayment()"
                    [disabled]="payProcessing() || !payAmount || payAmount <= 0"
                  >
                    @if (payProcessing()) {
                      <span class="pay-spinner"></span> Processing...
                    } @else {
                      Pay {{ payAmount ? (payAmount | currency) : '' }}
                    }
                  </button>
                </div>
              } @else {
                <div class="pay-actions">
                  <button class="pay-submit-btn" (click)="closePayDialog()">Done</button>
                </div>
              }
            </div>
          </div>
        </div>
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
      .pay-btn {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        margin-top: 6px;
        padding: 6px 14px;
        font-size: 12px;
        font-weight: 600;
        color: #fff;
        background: #2563eb;
        border: none;
        border-radius: 8px;
        cursor: pointer;
        transition: background 0.2s;
      }
      .pay-btn:hover {
        background: #1d4ed8;
      }
      .pay-btn .material-symbols-outlined {
        font-size: 16px;
      }
      /* ── Payment Dialog ── */
      .pay-overlay {
        position: fixed;
        inset: 0;
        background: rgba(0, 0, 0, 0.45);
        display: flex;
        align-items: center;
        justify-content: center;
        z-index: 1000;
      }
      .pay-dialog {
        background: #fff;
        border-radius: 16px;
        width: 420px;
        max-width: 95vw;
        box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
        overflow: hidden;
      }
      .pay-dialog-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 18px 24px;
        background: #f8fafc;
        border-bottom: 1px solid #e2e8f0;
      }
      .pay-dialog-header h3 {
        display: flex;
        align-items: center;
        gap: 8px;
        margin: 0;
        font-size: 16px;
        font-weight: 700;
        color: #1e293b;
      }
      .pay-dialog-close {
        background: none;
        border: none;
        cursor: pointer;
        color: #64748b;
        padding: 4px;
        border-radius: 6px;
      }
      .pay-dialog-close:hover {
        background: #e2e8f0;
      }
      .pay-dialog-body {
        padding: 20px 24px 24px;
      }
      .pay-invoice-info {
        display: flex;
        justify-content: space-between;
        padding: 8px 0;
        border-bottom: 1px solid #f1f5f9;
      }
      .pay-label {
        font-size: 13px;
        color: #64748b;
        font-weight: 500;
      }
      .pay-value {
        font-size: 14px;
        font-weight: 600;
        color: #1e293b;
      }
      .pay-balance {
        color: #d97706;
      }
      .pay-field {
        margin-top: 16px;
      }
      .pay-field label {
        display: block;
        font-size: 13px;
        font-weight: 600;
        color: #475569;
        margin-bottom: 6px;
      }
      .pay-field input,
      .pay-field select {
        width: 100%;
        padding: 10px 12px;
        font-size: 14px;
        border: 1px solid #cbd5e1;
        border-radius: 8px;
        background: #fff;
        color: #1e293b;
        outline: none;
        box-sizing: border-box;
      }
      .pay-field input:focus,
      .pay-field select:focus {
        border-color: #2563eb;
        box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
      }
      .pay-actions {
        display: flex;
        gap: 10px;
        justify-content: flex-end;
        margin-top: 20px;
      }
      .pay-cancel-btn {
        padding: 10px 20px;
        font-size: 14px;
        font-weight: 600;
        color: #475569;
        background: #f1f5f9;
        border: 1px solid #e2e8f0;
        border-radius: 8px;
        cursor: pointer;
      }
      .pay-cancel-btn:hover {
        background: #e2e8f0;
      }
      .pay-submit-btn {
        padding: 10px 24px;
        font-size: 14px;
        font-weight: 600;
        color: #fff;
        background: #2563eb;
        border: none;
        border-radius: 8px;
        cursor: pointer;
        display: flex;
        align-items: center;
        gap: 6px;
      }
      .pay-submit-btn:hover:not(:disabled) {
        background: #1d4ed8;
      }
      .pay-submit-btn:disabled {
        opacity: 0.6;
        cursor: not-allowed;
      }
      .pay-error {
        margin-top: 12px;
        padding: 10px 14px;
        background: #fef2f2;
        color: #dc2626;
        border-radius: 8px;
        font-size: 13px;
        font-weight: 500;
      }
      .pay-success {
        margin-top: 12px;
        padding: 10px 14px;
        background: #f0fdf4;
        color: #16a34a;
        border-radius: 8px;
        font-size: 13px;
        font-weight: 500;
      }
      .pay-spinner {
        width: 14px;
        height: 14px;
        border: 2px solid rgba(255, 255, 255, 0.3);
        border-top-color: #fff;
        border-radius: 50%;
        animation: spin 0.6s linear infinite;
        display: inline-block;
      }
      @keyframes spin {
        to {
          transform: rotate(360deg);
        }
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
        // Refresh invoice list
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
