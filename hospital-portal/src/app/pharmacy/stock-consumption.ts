import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { ToastService } from '../core/toast.service';
import {
  StockConsumptionLine,
  StockConsumptionReport,
  StockCountService,
  StockStore,
} from '../services/stock-count.service';
import { saveBlob } from '../shared/download-file';
import { refusalReason } from '../shared/refusal-reason';
import { countUnit } from '../shared/pipes/count-unit.pipe';
import { DashPipe } from '../shared/pipes/dash.pipe';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { PluralKeyPipe } from '../shared/i18n/plural-key';
import { FormControlStyleDirective } from '../shared/forms/form-control-style.directive';

/** ISO date (YYYY-MM-DD) in the reader's calendar. */
function localIsoDate(d: Date): string {
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${mm}-${dd}`;
}

/** One cell of a CSV line: quoted when it holds a separator, a quote or a line break. */
export function csvCell(value: string | number | null | undefined): string {
  const text = value === null || value === undefined ? '' : String(value);
  // A cell that starts like a formula is read as text by a spreadsheet, not run.
  const safe = /^[=+\-@\t\r]/.test(text) && Number.isNaN(Number(text)) ? `'${text}` : text;
  return /[";\n\r]/.test(safe) ? `"${safe.replaceAll('"', '""')}"` : safe;
}

/**
 * QA D4183 (r81): what a store issued and took back over a period, per medicine and recorded unit. The report of
 * #1344 had no screen. Period (30 days by default, a year at most: the server refuses more), store, medicine,
 * and an export of what is shown.
 *
 * QA D4192: the movements recorded before the unit was kept with each movement are shown with their quantities
 * and « unité non enregistrée »; the line was blank. They stay a line of their own: their unit is not known, so
 * they are never added to the others.
 */
import { CatalogFormPipe, catalogFormLabel } from './catalog-label';
@Component({
  selector: 'app-stock-consumption',
  standalone: true,
  imports: [
    CatalogFormPipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    DashPipe,
    ServerTimePipe,
    PluralKeyPipe,
    FormControlStyleDirective,
  ],
  templateUrl: './stock-consumption.html',
  styleUrl: './stock-consumption.scss',
  changeDetection: ChangeDetectionStrategy.Eager,
})
export class StockConsumptionComponent implements OnInit {
  private readonly api = inject(StockCountService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly stores = signal<StockStore[]>([]);
  readonly storesLoaded = signal(false);
  readonly storesFailed = signal(false);
  selectedStoreId = '';

  from = localIsoDate(new Date(Date.now() - 29 * 24 * 60 * 60 * 1000));
  to = localIsoDate(new Date());
  search = '';

  readonly report = signal<StockConsumptionReport | null>(null);
  readonly loading = signal(false);
  /** The server's refusal (a period it does not take), or the generic failure. */
  readonly failure = signal<string | null>(null);
  private loadSeq = 0;

  ngOnInit(): void {
    this.loadStores();
  }

  loadStores(): void {
    this.storesFailed.set(false);
    this.api.stores().subscribe({
      next: (list) => {
        this.stores.set(list ?? []);
        this.storesLoaded.set(true);
        if (!this.selectedStoreId && this.stores().length > 0) {
          this.selectedStoreId = this.stores()[0].pharmacyId;
        }
        this.load();
      },
      error: () => {
        this.storesLoaded.set(true);
        this.storesFailed.set(true);
      },
    });
  }

  load(): void {
    if (!this.selectedStoreId || !this.from || !this.to) return;
    const seq = ++this.loadSeq;
    this.loading.set(true);
    this.failure.set(null);
    this.api.consumption(this.selectedStoreId, this.from, this.to).subscribe({
      next: (report) => {
        if (seq !== this.loadSeq) return;
        this.report.set(report);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        if (seq !== this.loadSeq) return;
        this.report.set(null);
        this.loading.set(false);
        this.failure.set(
          refusalReason(err) ?? this.translate.instant('STOCK_CONSUMPTION.LOAD_FAILED'),
        );
      },
    });
  }

  /** The lines the medicine search keeps. */
  visible(): StockConsumptionLine[] {
    const query = this.search.trim().toLocaleLowerCase();
    return (this.report()?.items ?? []).filter(
      (line) => !query || (line.medicationName ?? '').toLocaleLowerCase().includes(query),
    );
  }

  /** Issued minus taken back. */
  net(line: StockConsumptionLine): number {
    return Number(line.issuedQuantity ?? 0) - Number(line.returnedQuantity ?? 0);
  }

  /** « 51 comprimés », or the bare number when the unit of the movements was not recorded. */
  quantity(value: number | null | undefined, line: StockConsumptionLine): string {
    return countUnit(Number(value ?? 0), line.unitHistoryComparable ? line.unit : null);
  }

  storeName(): string {
    return this.stores().find((s) => s.pharmacyId === this.selectedStoreId)?.pharmacyName ?? '';
  }

  /** The lines shown, as a spreadsheet file (semicolons, UTF-8 with its mark: read by Excel in French). */
  exportCsv(): void {
    const lines = this.visible();
    // The button is disabled while nothing is shown.
    if (lines.length === 0) return;
    const t = (key: string): string => this.translate.instant(`STOCK_CONSUMPTION.${key}`);
    const header: (string | number)[] = [
      t('COL.MEDICINE'),
      t('COL.FORM'),
      t('COL.UNIT'),
      t('COL.ISSUED'),
      t('COL.RETURNED'),
      t('COL.NET'),
      t('COL.MOVEMENTS'),
    ];
    const rows: (string | number)[][] = lines.map((line) => [
      line.medicationName ?? '',
      line.form ? catalogFormLabel(this.translate, line.form) : '',
      line.unitHistoryComparable ? (line.unit ?? '') : t('UNIT_NOT_RECORDED'),
      Number(line.issuedQuantity ?? 0),
      Number(line.returnedQuantity ?? 0),
      this.net(line),
      line.sourceMovements,
    ]);
    const meta: (string | number)[][] = [
      [t('STORE'), this.storeName()],
      [t('PERIOD'), `${this.report()?.from ?? this.from} / ${this.report()?.to ?? this.to}`],
      [],
    ];
    const csv = [...meta, header, ...rows].map((row) => row.map(csvCell).join(';')).join('\r\n');
    saveBlob(
      new Blob(['﻿', csv, '\r\n'], { type: 'text/csv;charset=utf-8' }),
      `${t('FILE_NAME')}-${this.from}-${this.to}.csv`,
    );
  }
}
