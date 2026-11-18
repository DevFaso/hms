import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';

import { BillingService, InvoiceRefund } from '../../services/billing.service';
import { CfaPipe } from '../../shared/pipes/cfa.pipe';
import { KeyLabelPipe } from '../../shared/pipes/key-label.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';

/**
 * Billing-money plan PR 8 (QA D1900): "Remboursements à approuver", the hospital's refunds waiting on someone: a
 * decision by a hospital admin or an accountant who did not ask for it (owner decision D-3), or the hand-over at the
 * till once approved. Each row opens its invoice, where the steps are taken.
 */
@Component({
  selector: 'app-refund-queue',
  standalone: true,
  imports: [CommonModule, TranslateModule, CfaPipe, KeyLabelPipe, ServerTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="refund-queue" data-testid="refund-queue" aria-labelledby="refund-queue-title">
      <h2 id="refund-queue-title">{{ 'BILLING.REFUNDS.QUEUE_TITLE' | translate }}</h2>
      @if (loading()) {
        <p class="field-hint" role="status">{{ 'COMMON.LOADING' | translate }}</p>
      } @else if (failed()) {
        <p class="field-hint" role="alert">{{ 'BILLING.REFUNDS.LOAD_FAILED' | translate }}</p>
      } @else if (rows().length === 0) {
        <p class="field-hint" data-testid="refund-queue-empty">
          {{ 'BILLING.REFUNDS.QUEUE_EMPTY' | translate }}
        </p>
      } @else {
        <table class="queue-table">
          <caption class="sr-only">
            {{
              'BILLING.REFUNDS.QUEUE_TITLE' | translate
            }}
          </caption>
          <thead>
            <tr>
              <th scope="col">{{ 'BILLING.REFUNDS.INVOICE' | translate }}</th>
              <th scope="col">{{ 'PHARMACY_CHECKOUT.PATIENT' | translate }}</th>
              <th scope="col" class="amount">{{ 'BILLING.REFUNDS.AMOUNT' | translate }}</th>
              <th scope="col">{{ 'BILLING.REFUNDS.REASON' | translate }}</th>
              <th scope="col">{{ 'COMMON.STATUS' | translate }}</th>
              <th scope="col">
                <span class="sr-only">{{ 'COMMON.ACTIONS' | translate }}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            @for (r of rows(); track r.id) {
              <tr [attr.data-testid]="'queue-' + r.id">
                <td>{{ r.invoiceNumber || '—' }}</td>
                <td>{{ r.patientName || '—' }}</td>
                <td class="amount">{{ r.amount | cfa }}</td>
                <td>
                  {{ r.reason }}
                  @if (r.requestedByName) {
                    <div class="cell-secondary">
                      {{ 'BILLING.REFUNDS.BY' | translate: { name: r.requestedByName } }}
                      @if (r.requestedAt) {
                        · {{ r.requestedAt | serverTime | date: 'short' }}
                      }
                    </div>
                  }
                </td>
                <td>{{ r.status | keyLabel: 'BILLING.REFUNDS.STATUSES.' }}</td>
                <td>
                  <button
                    type="button"
                    class="btn-secondary btn-sm"
                    [attr.data-testid]="'queue-open-' + r.id"
                    (click)="openInvoice.emit(r.invoiceId)"
                  >
                    {{ 'BILLING.REFUNDS.OPEN_INVOICE' | translate }}
                  </button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>
  `,
  styles: [
    `
      .refund-queue {
        margin: 1rem 0;
        padding: 1rem;
        border: 1px solid var(--border-color, #e5e7eb);
        border-radius: 8px;
        background: var(--surface, #fff);
      }
      .refund-queue h2 {
        margin: 0 0 0.5rem;
        font-size: 1.1rem;
      }
      .queue-table {
        width: 100%;
        border-collapse: collapse;
        font-size: 0.875rem;
      }
      .queue-table th,
      .queue-table td {
        padding: 0.5rem;
        text-align: left;
        vertical-align: top;
        border-bottom: 1px solid var(--border-color, #e5e7eb);
      }
      .amount {
        text-align: right;
        white-space: nowrap;
      }
      .cell-secondary {
        font-size: 0.78rem;
        color: #64748b;
      }
      .sr-only {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip: rect(0 0 0 0);
      }
      @media (max-width: 640px) {
        .queue-table {
          display: block;
          overflow-x: auto;
        }
      }
    `,
  ],
})
export class RefundQueueComponent {
  private readonly billing = inject(BillingService);
  private readonly destroyRef = inject(DestroyRef);

  /** The invoice of a row, for the page to open its detail. */
  readonly openInvoice = output<string>();

  readonly rows = signal<InvoiceRefund[]>([]);
  readonly loading = signal(false);
  readonly failed = signal(false);

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.billing
      .getRefundQueue()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => {
          this.rows.set(rows ?? []);
          this.loading.set(false);
        },
        error: () => {
          this.rows.set([]);
          this.failed.set(true);
          this.loading.set(false);
        },
      });
  }
}
