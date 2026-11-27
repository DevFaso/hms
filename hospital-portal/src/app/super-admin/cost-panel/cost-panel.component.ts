import { FormControlStyleDirective } from '../../shared/forms/form-control-style.directive';
import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule, DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  ChargebackReport,
  ChargebackRow,
  ChargebackService,
} from '../../services/chargeback.service';
import { CfaPipe } from '../../shared/pipes/cfa.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { refusalReason } from '../../shared/refusal-reason';
import { currentLocale } from '../../shared/i18n/app-locale';

interface WindowState {
  from: string;
  to: string;
}

/**
 * Super-admin per-hospital cost page (roadmap row 44 follow-on).
 *
 * <p>QA D4305-D4307: the page states the period it shows (the server's default
 * one when none is chosen), the rate the amounts are computed with, amounts in
 * F CFA, one total, the reason of a refused period, and exports the rows shown.
 *
 * <p>Flag-off contract: the backing endpoint answers 204 (or 404) when cost
 * tracking is off; the page says so instead of showing an error.
 */
@Component({
  selector: 'app-cost-panel',
  standalone: true,
  imports: [
    FormControlStyleDirective,
    CommonModule,
    FormsModule,
    TranslateModule,
    DecimalPipe,
    DatePipe,
    CfaPipe,
    ServerTimePipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="cost-panel" data-testid="cost-panel">
      <header class="cost-panel__header">
        <h1>{{ 'COST_PANEL.TITLE' | translate }}</h1>
        <p class="cost-panel__subtitle">{{ 'COST_PANEL.SUBTITLE' | translate }}</p>
      </header>

      <form
        class="cost-panel__filters form-card"
        (submit)="reload($event)"
        data-testid="cost-panel-filters"
      >
        <div class="form-row form-group">
          <label for="cost-from">{{ 'COST_PANEL.FROM' | translate }}</label>
          <input
            appFormControl
            id="cost-from"
            name="from"
            type="date"
            [(ngModel)]="window.from"
            data-testid="cost-from"
          />
        </div>
        <div class="form-row form-group">
          <label for="cost-to">{{ 'COST_PANEL.TO' | translate }}</label>
          <input
            appFormControl
            id="cost-to"
            name="to"
            type="date"
            [attr.min]="window.from || null"
            [(ngModel)]="window.to"
            data-testid="cost-to"
          />
        </div>
        <button type="submit" class="btn-primary" [disabled]="loading()" data-testid="cost-reload">
          {{ loading() ? ('COST_PANEL.LOADING' | translate) : ('COST_PANEL.REFRESH' | translate) }}
        </button>
        @if (rows().length > 0) {
          <button
            type="button"
            class="btn-secondary"
            [disabled]="loading()"
            (click)="exportCsv()"
            data-testid="cost-export"
          >
            {{ 'COST_PANEL.EXPORT_CSV' | translate }}
          </button>
        }
      </form>

      @if (featureDisabled()) {
        <p class="cost-panel__empty" data-testid="cost-disabled">
          {{ 'COST_PANEL.FEATURE_DISABLED' | translate }}
        </p>
      }

      @if (error()) {
        <p class="cost-panel__error" role="alert" data-testid="cost-error">
          {{ errorReason() ?? ('COST_PANEL.ERROR' | translate) }}
        </p>
      }

      @if (report(); as r) {
        @if (!featureDisabled() && !error()) {
          <p class="cost-panel__period" data-testid="cost-period">
            {{
              'COST_PANEL.PERIOD'
                | translate
                  : {
                      from: (r.from | serverTime | date: 'mediumDate'),
                      to: (r.to | serverTime | date: 'mediumDate'),
                    }
            }}
          </p>
          <p class="cost-panel__rate" data-testid="cost-rate">
            @if (r.ratePerAuditEvent > 0) {
              {{
                'COST_PANEL.RATE' | translate: { amount: (r.ratePerAuditEvent | cfa: r.currency) }
              }}
            } @else {
              {{ 'COST_PANEL.NO_RATE' | translate }}
            }
          </p>
        }
      }

      @if (!featureDisabled() && !error() && rows().length > 0) {
        <div class="cost-panel__table">
          <table class="data-table" data-testid="cost-rows">
            <thead>
              <tr>
                <th scope="col">{{ 'COST_PANEL.COL_HOSPITAL' | translate }}</th>
                <th scope="col" class="num">{{ 'COST_PANEL.COL_AUDIT_EVENTS' | translate }}</th>
                <th scope="col" class="num">{{ 'COST_PANEL.COL_AMOUNT' | translate }}</th>
              </tr>
            </thead>
            <tbody>
              @for (row of rows(); track trackByHospitalId($index, row)) {
                <tr [attr.data-hospital-id]="row.hospitalId">
                  <td>{{ row.hospitalName }}</td>
                  <td class="num">{{ row.auditEventCount | number: '1.0-0' }}</td>
                  <td class="num" data-testid="cost-amount">
                    {{ hasRate() ? (row.chargebackAmount | cfa: row.currency) : '—' }}
                  </td>
                </tr>
              }
            </tbody>
            <tfoot>
              <tr data-testid="cost-total">
                <th scope="row">{{ 'COST_PANEL.TOTAL' | translate }}</th>
                <td class="num">{{ report()?.totalAuditEvents | number: '1.0-0' }}</td>
                <td class="num">
                  {{ hasRate() ? (report()?.totalAmount | cfa: report()?.currency) : '—' }}
                </td>
              </tr>
            </tfoot>
          </table>
        </div>
      }

      @if (!featureDisabled() && !error() && !loading() && rows().length === 0) {
        <p class="cost-panel__empty" data-testid="cost-empty">
          {{ 'COST_PANEL.NO_DATA' | translate }}
        </p>
      }
    </section>
  `,
  styles: [
    `
      .cost-panel {
        padding: 1.5rem;
      }
      .cost-panel__header h1 {
        margin-bottom: 0.25rem;
      }
      .cost-panel__subtitle {
        margin-top: 0;
        color: var(--text-secondary);
      }
      .cost-panel__filters {
        display: flex;
        flex-wrap: wrap;
        gap: 1rem;
        align-items: end;
        margin: 1rem 0 1.5rem;
      }
      .form-row {
        display: flex;
        flex-direction: column;
      }
      .form-row label {
        font-weight: 500;
        margin-bottom: 0.25rem;
      }
      .cost-panel__period {
        font-weight: 600;
        margin: 0 0 0.25rem;
      }
      .cost-panel__rate {
        margin: 0 0 1rem;
        color: var(--text-secondary);
      }
      .cost-panel__table {
        overflow-x: auto;
      }
      .cost-panel__error {
        color: var(--danger-text, #b00020);
      }
      .cost-panel__empty {
        color: var(--text-secondary);
        font-style: italic;
      }
      tfoot th,
      tfoot td {
        font-weight: 700;
        border-top: 2px solid var(--border-color, #cbd5e1);
      }
      tfoot th {
        text-align: left;
      }
      .num {
        text-align: right;
        font-variant-numeric: tabular-nums;
        white-space: nowrap;
      }
      @media (max-width: 600px) {
        .cost-panel {
          padding: 1rem;
        }
      }
    `,
  ],
})
export class CostPanelComponent implements OnInit {
  protected readonly report = signal<ChargebackReport | null>(null);
  protected readonly rows = computed<ChargebackRow[]>(() => this.report()?.rows ?? []);
  /** No rate configured: an amount is not a zero, it is absent. */
  protected readonly hasRate = computed(() => (this.report()?.ratePerAuditEvent ?? 0) > 0);
  protected readonly loading = signal(false);
  protected readonly error = signal(false);
  /** The server's reason for a refused period, in the reader's language. */
  protected readonly errorReason = signal<string | null>(null);
  protected readonly featureDisabled = signal(false);

  protected window: WindowState = { from: '', to: '' };

  private readonly service = inject(ChargebackService);
  private readonly translate = inject(TranslateService);

  ngOnInit(): void {
    this.fetch();
  }

  protected reload(event: Event): void {
    event.preventDefault();
    this.fetch();
  }

  protected trackByHospitalId(_index: number, row: ChargebackRow): string {
    return row.hospitalId;
  }

  /** QA D4306: the rows on screen, for the period on screen, as a CSV file. */
  protected exportCsv(): void {
    const r = this.report();
    if (!r || r.rows.length === 0) return;
    const blob = new Blob([this.toCsv(r)], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${this.translate.instant('COST_PANEL.EXPORT_FILE')}_${r.from}_${r.to}.csv`;
    link.click();
    URL.revokeObjectURL(url);
  }

  /**
   * The file a French spreadsheet opens as columns: semicolons (the comma is the
   * decimal mark there), a byte-order mark so accents survive, plain numbers.
   */
  toCsv(r: ChargebackReport): string {
    const separator = currentLocale().startsWith('en') ? ',' : ';';
    const t = (key: string): string => this.translate.instant(key);
    const cell = (value: string | number): string => {
      const text = String(value);
      // A leading =, +, - or @ would be read as a formula by a spreadsheet.
      const safe = /^[=+\-@\t\r]/.test(text) ? `'${text}` : text;
      return /[";,\n\r]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
    };
    const amount = (value: number): string | number => (r.ratePerAuditEvent > 0 ? value : '');
    const lines = [
      [
        t('COST_PANEL.COL_HOSPITAL'),
        t('COST_PANEL.FROM'),
        t('COST_PANEL.TO'),
        t('COST_PANEL.COL_AUDIT_EVENTS'),
        `${t('COST_PANEL.COL_AMOUNT')} (${r.currency})`,
      ],
      ...r.rows.map((row) => [
        row.hospitalName,
        r.from,
        r.to,
        row.auditEventCount,
        amount(row.chargebackAmount),
      ]),
      [t('COST_PANEL.TOTAL'), r.from, r.to, r.totalAuditEvents, amount(r.totalAmount)],
    ];
    return '﻿' + lines.map((line) => line.map(cell).join(separator)).join('\r\n') + '\r\n';
  }

  private fetch(): void {
    this.loading.set(true);
    this.error.set(false);
    this.errorReason.set(null);
    this.featureDisabled.set(false);
    this.service.report(this.window.from || undefined, this.window.to || undefined).subscribe({
      next: (report) => {
        // 204 (no body): the cost flag is off (D248).
        this.featureDisabled.set(report === null);
        this.report.set(report);
        if (report) {
          // QA D4307: the fields show the period the rows cover, default included.
          this.window = { from: report.from, to: report.to };
        }
        this.loading.set(false);
      },
      error: (err: { status?: number }) => {
        this.report.set(null);
        this.loading.set(false);
        // 404 from the backing endpoint means the flag is off; anything else is
        // an error, said with the server's reason when it gave one (D4307).
        if (err?.status === 404) {
          this.featureDisabled.set(true);
        } else {
          this.errorReason.set(refusalReason(err));
          this.error.set(true);
        }
      },
    });
  }
}
