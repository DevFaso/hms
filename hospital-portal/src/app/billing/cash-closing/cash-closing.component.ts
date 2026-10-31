import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';

import { BillingService, CashClosing } from '../../services/billing.service';
import { CfaPipe } from '../../shared/pipes/cfa.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';

/** Today in the browser's calendar, as YYYY-MM-DD. */
function today(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/**
 * QA D1059: the cashier's daily cash closing ("clôture de caisse"). At the end
 * of a shift the cashier counts the till against what they received: the
 * invoice payments they confirmed and the pharmacy payments they took that
 * day, totalled per payment method, with what was reversed or voided since
 * (not in the till) shown apart. Read-only, printable. The billing page had
 * no such screen and no word for it.
 */
@Component({
  selector: 'app-cash-closing',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, CfaPipe, ServerTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="cash-closing" data-testid="cash-closing" aria-labelledby="cash-closing-title">
      <div class="cash-closing-head">
        <h2 id="cash-closing-title">{{ 'BILLING.CASH_CLOSING.TITLE' | translate }}</h2>
        <div class="cash-closing-controls no-print">
          <label for="cash-closing-date">{{ 'BILLING.CASH_CLOSING.DATE' | translate }}</label>
          <input
            id="cash-closing-date"
            type="date"
            [max]="maxDate"
            [ngModel]="date()"
            (ngModelChange)="pick($event)"
          />
          <button type="button" class="btn-secondary" (click)="print()" [disabled]="!closing()">
            <span class="material-symbols-outlined" aria-hidden="true">print</span>
            {{ 'BILLING.CASH_CLOSING.PRINT' | translate }}
          </button>
        </div>
      </div>
      @if (loading()) {
        <p class="field-hint" role="status">{{ 'BILLING.CASH_CLOSING.LOADING' | translate }}</p>
      } @else if (failed()) {
        <p class="field-hint" role="alert">{{ 'BILLING.CASH_CLOSING.LOAD_FAILED' | translate }}</p>
      } @else if (closing(); as c) {
        <p class="cash-closing-who">
          {{
            'BILLING.CASH_CLOSING.SUBTITLE'
              | translate
                : { cashier: c.cashierName || '—', date: (c.date | serverTime | date: 'longDate') }
          }}
        </p>
        @if (c.entries.length === 0) {
          <p class="field-hint" data-testid="cash-closing-empty">
            {{ 'BILLING.CASH_CLOSING.EMPTY' | translate }}
          </p>
        } @else {
          <table class="data-table" data-testid="cash-closing-totals">
            <caption class="sr-only">
              {{
                'BILLING.CASH_CLOSING.BY_METHOD' | translate
              }}
            </caption>
            <thead>
              <tr>
                <th scope="col">{{ 'BILLING.CASH_CLOSING.SOURCE' | translate }}</th>
                <th scope="col">{{ 'BILLING.PAYMENT_METHOD' | translate }}</th>
                <th scope="col" class="numeric">{{ 'BILLING.CASH_CLOSING.COUNT' | translate }}</th>
                <th scope="col" class="numeric">{{ 'BILLING.CASH_CLOSING.AMOUNT' | translate }}</th>
              </tr>
            </thead>
            <tbody>
              @for (t of c.byMethod; track t.source + t.method) {
                <tr>
                  <td>{{ 'BILLING.CASH_CLOSING.SOURCES.' + t.source | translate }}</td>
                  <td>{{ 'BILLING.PAYMENT_METHODS.' + t.method | translate }}</td>
                  <td class="numeric">{{ t.count }}</td>
                  <td class="numeric">{{ t.amount | cfa }}</td>
                </tr>
              }
            </tbody>
            <tfoot>
              <tr>
                <th scope="row" colspan="2">{{ 'BILLING.CASH_CLOSING.TOTAL' | translate }}</th>
                <td class="numeric">{{ c.receivedCount }}</td>
                <td class="numeric" data-testid="cash-closing-total">
                  {{ c.receivedAmount | cfa }}
                </td>
              </tr>
              @if (c.reversedCount > 0) {
                <tr>
                  <th scope="row" colspan="2">{{ 'BILLING.CASH_CLOSING.REVERSED' | translate }}</th>
                  <td class="numeric">{{ c.reversedCount }}</td>
                  <td class="numeric">{{ c.reversedAmount | cfa }}</td>
                </tr>
              }
            </tfoot>
          </table>
          <table class="data-table" data-testid="cash-closing-entries">
            <caption class="sr-only">
              {{
                'BILLING.CASH_CLOSING.ENTRIES' | translate
              }}
            </caption>
            <thead>
              <tr>
                <th scope="col">{{ 'BILLING.CASH_CLOSING.TIME' | translate }}</th>
                <th scope="col">{{ 'BILLING.CASH_CLOSING.DOCUMENT' | translate }}</th>
                <th scope="col">{{ 'PHARMACY_CHECKOUT.PATIENT' | translate }}</th>
                <th scope="col">{{ 'BILLING.PAYMENT_METHOD' | translate }}</th>
                <th scope="col">{{ 'BILLING.CASH_CLOSING.REFERENCE' | translate }}</th>
                <th scope="col" class="numeric">{{ 'BILLING.CASH_CLOSING.AMOUNT' | translate }}</th>
              </tr>
            </thead>
            <tbody>
              @for (e of c.entries; track e.id) {
                <tr [class.reversed]="isReversed(e.status)">
                  <td>{{ e.receivedAt | serverTime | date: 'shortTime' }}</td>
                  <td>{{ e.document || '—' }}</td>
                  <td>{{ e.patientName || '—' }}</td>
                  <td>{{ 'BILLING.PAYMENT_METHODS.' + e.method | translate }}</td>
                  <td>{{ e.reference || '—' }}</td>
                  <td class="numeric">
                    {{ e.amount | cfa }}
                    @if (isReversed(e.status)) {
                      <span class="reversed-tag">{{
                        'BILLING.CASH_CLOSING.REVERSED_TAG' | translate
                      }}</span>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        }
      }
    </section>
  `,
  styles: [
    `
      .cash-closing {
        margin: 1rem 0;
        padding: 1rem;
        border: 1px solid var(--border-color, #e5e7eb);
        border-radius: 8px;
        background: var(--surface, #fff);
      }
      .cash-closing-head {
        display: flex;
        flex-wrap: wrap;
        gap: 0.75rem;
        align-items: center;
        justify-content: space-between;
      }
      .cash-closing-controls {
        display: flex;
        flex-wrap: wrap;
        gap: 0.5rem;
        align-items: center;
      }
      .numeric {
        text-align: right;
      }
      tr.reversed td {
        color: #6b7280;
        text-decoration: line-through;
      }
      .reversed-tag {
        margin-left: 0.25rem;
        font-size: 0.75rem;
        text-decoration: none;
      }
      .data-table {
        width: 100%;
        margin-top: 0.75rem;
      }
      .sr-only {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip: rect(0 0 0 0);
      }
    `,
  ],
})
export class CashClosingComponent {
  private readonly billing = inject(BillingService);
  private readonly destroyRef = inject(DestroyRef);

  readonly maxDate = today();
  readonly date = signal(today());
  readonly closing = signal<CashClosing | null>(null);
  readonly loading = signal(false);
  readonly failed = signal(false);
  private request = 0;

  constructor() {
    this.load();
  }

  pick(value: string): void {
    this.date.set(value || today());
    this.load();
  }

  print(): void {
    window.print();
  }

  isReversed(status: string): boolean {
    return status === 'REVERSED' || status === 'VOIDED';
  }

  private load(): void {
    const seq = ++this.request;
    this.loading.set(true);
    this.failed.set(false);
    this.billing
      .getCashClosing(this.date())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (c) => {
          if (seq !== this.request) return;
          this.closing.set(c ?? null);
          this.loading.set(false);
        },
        error: () => {
          if (seq !== this.request) return;
          this.closing.set(null);
          this.failed.set(true);
          this.loading.set(false);
        },
      });
  }
}
