import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  inject,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  PRICE_LIST_CSV_HEADER,
  PriceListImportReport,
  PriceListService,
} from '../../services/price-list.service';
import { ToastService } from '../../core/toast.service';
import { CfaPipe } from '../../shared/pipes/cfa.pipe';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { DateTimePipe } from '../../shared/pipes/date-time.pipe';
import { DialogA11yDirective } from '../../shared/a11y/dialog-a11y.directive';
import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { refusalReason, refusedFieldReasons } from '../../shared/refusal-reason';
import { saveBlob } from '../../shared/download-file';
import { refuseFields } from '../../shared/forms/invalid-fields';

const FILE_FIELD_ID = 'pl-import-file';

/**
 * A refusal naming the item's code or link (`fieldErrors.code` / `fieldErrors.linkKey`): another person created
 * the same code or link between the check and the apply.
 */
export function concurrentInsert(err: unknown): boolean {
  const fieldErrors = (err as { error?: { fieldErrors?: Record<string, unknown> } } | null)?.error
    ?.fieldErrors;
  return !!fieldErrors && ('code' in fieldErrors || 'linkKey' in fieldErrors);
}

/** The CSV columns a refusal can name (the import's header), each with a label on screen. */
export const PRICE_LIST_CSV_COLUMNS: readonly string[] = [
  'code',
  'libelle',
  'categorie',
  'prix',
  'date_effet',
  'liaison_type',
  'liaison_cle',
];

/** The two columns that link a price to a lab test, an imaging exam or a medicine. */
const LINK_COLUMNS: readonly string[] = ['liaison_type', 'liaison_cle'];

/** The server's limit, checked here first so a too-large file is refused before it is sent. */
export const PRICE_LIST_CSV_MAX_BYTES = 1_048_576;

/**
 * The template the screen offers: the import's header only, UTF-8 with a BOM
 * (so Excel FR opens the accents right) and CRLF, ready to fill in a spreadsheet.
 */
export function priceListTemplate(): Blob {
  return new Blob(['\uFEFF' + PRICE_LIST_CSV_HEADER + '\r\n'], { type: 'text/csv;charset=utf-8' });
}

/**
 * Billing-money plan PR 4 (scorecard M1): "Importer une grille (CSV)" on
 * /billing/tarifs. The reader picks a file and checks it ("Vérifier le
 * fichier", a dry run that writes nothing): every row is listed with what it
 * would do, and every refused row with its line, its column and the server's
 * reason. "Appliquer l'import" is enabled only after a clean check of that
 * very file (same file, same option); the apply writes everything or nothing.
 * Only the hospital admin and the accountant are offered this dialog.
 */
@Component({
  selector: 'app-price-list-import',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslateModule,
    CfaPipe,
    EnumLabelPipe,
    ServerTimePipe,
    DateTimePipe,
    DialogA11yDirective,
    SubmitGuardDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './price-list-import.component.html',
  styleUrl: './price-list-import.component.scss',
})
export class PriceListImportComponent {
  private readonly api = inject(PriceListService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** The dialog was closed (Escape, the backdrop, "Fermer"). */
  readonly closed = output<void>();
  /** A file was applied: the page reloads its list. */
  readonly applied = output<PriceListImportReport>();

  readonly file = signal<File | null>(null);
  readonly replaceScheduled = signal(false);
  readonly busy = signal<'check' | 'apply' | null>(null);
  readonly report = signal<PriceListImportReport | null>(null);
  /** The server's reason when the whole file was refused (header, size, encoding). */
  readonly fileError = signal<string | null>(null);
  readonly onlyRefused = signal(false);

  readonly validRows = computed(() => {
    const r = this.report();
    return r ? r.totalRows - r.refused : 0;
  });

  readonly visibleRows = computed(() => {
    const rows = this.report()?.rows ?? [];
    return this.onlyRefused() ? rows.filter((row) => row.outcome === 'REFUSED') : rows;
  });

  /**
   * QA r65 D2765: a refusal on the link columns. A new hospital's file linked
   * its lab prices to tests that did not exist (or were not active) yet, got
   * « liaison_cle : Aucun test de laboratoire… » on every such line, and was
   * told neither that the tests come first nor that a price can be imported
   * unlinked and linked later from the grid.
   */
  readonly linkRefused = computed(() =>
    (this.report()?.rows ?? []).some((row) =>
      (row.refusals ?? []).some((r) => !!r.column && LINK_COLUMNS.includes(r.column)),
    ),
  );

  /**
   * The column of a refusal as the reader knows it: its label, then the CSV
   * header name to look for in the file (« Clé de liaison (liaison_cle) »).
   * A column this build does not know is shown as the server named it.
   */
  columnLabel(column: string): string {
    if (!PRICE_LIST_CSV_COLUMNS.includes(column)) return column;
    return `${this.translate.instant('PLATFORM_SETUP.IMPORT.COLUMN.' + column)} (${column})`;
  }

  /** Apply only after a clean dry run of the file and option now chosen. */
  readonly canApply = computed(() => {
    const r = this.report();
    return (
      this.busy() === null &&
      this.file() !== null &&
      r !== null &&
      r.result === 'DRY_RUN' &&
      r.refused === 0 &&
      r.totalRows > 0
    );
  });

  pickFile(event: Event): void {
    const input = event.target as HTMLInputElement | null;
    const picked = input?.files && input.files.length > 0 ? input.files[0] : null;
    this.file.set(picked);
    this.reset();
    if (picked && picked.size > PRICE_LIST_CSV_MAX_BYTES) {
      this.fileError.set(this.translate.instant('BILLING.PRICE_LIST.IMPORT.TOO_LARGE'));
    }
  }

  /** A different option means a different check: the last report no longer stands. */
  setReplaceScheduled(value: boolean): void {
    this.replaceScheduled.set(value);
    this.reset();
  }

  downloadTemplate(): void {
    saveBlob(priceListTemplate(), 'modele-grille-tarifaire.csv');
  }

  check(): void {
    this.send(true);
  }

  apply(): void {
    if (!this.canApply()) return;
    this.send(false);
  }

  close(): void {
    if (this.busy() === 'apply') return;
    this.closed.emit();
  }

  private reset(): void {
    this.report.set(null);
    this.fileError.set(null);
    this.onlyRefused.set(false);
  }

  private send(dryRun: boolean): void {
    const file = this.file();
    if (!file) {
      refuseFields(
        this.host.nativeElement,
        [FILE_FIELD_ID],
        [FILE_FIELD_ID],
        this.translate.instant('BILLING.PRICE_LIST.IMPORT.NO_FILE'),
      );
      return;
    }
    if (this.busy() !== null || file.size > PRICE_LIST_CSV_MAX_BYTES) return;
    this.busy.set(dryRun ? 'check' : 'apply');
    this.fileError.set(null);
    this.api
      .importCsv(file, dryRun, this.replaceScheduled())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (report) => {
          this.busy.set(null);
          this.report.set(report);
          if (report.result === 'APPLIED') {
            this.toast.success(
              this.translate.instant('BILLING.PRICE_LIST.IMPORT.APPLIED', {
                newItems: report.newItems,
                newPrices: report.newPrices,
                unchanged: report.unchanged,
              }),
            );
            this.applied.emit(report);
          } else if (report.result === 'ALREADY_IMPORTED') {
            this.toast.info(this.translate.instant('BILLING.PRICE_LIST.IMPORT.ALREADY_APPLIED'));
          }
        },
        error: (err: unknown) => {
          this.busy.set(null);
          const body = err instanceof HttpErrorResponse ? err.error : null;
          if (err instanceof HttpErrorResponse && err.status === 422 && body?.rows) {
            // The apply refused a row and wrote nothing: the report says which.
            this.report.set(body as PriceListImportReport);
            this.onlyRefused.set(true);
            this.toast.error(this.translate.instant('BILLING.PRICE_LIST.IMPORT.APPLY_FAILED'));
            return;
          }
          this.report.set(null);
          // A refusal of the whole file names the field "file": it is marked under the picker.
          const refused = refusedFieldReasons(err, { file: FILE_FIELD_ID });
          if (refused.length > 0) {
            refuseFields(
              this.host.nativeElement,
              refused.map(([id, message]) => ({ id, message })),
              [FILE_FIELD_ID],
            );
            return;
          }
          if (concurrentInsert(err)) {
            // Someone created the same code or link meanwhile: the row has no line here; check again.
            this.fileError.set(
              this.translate.instant('BILLING.PRICE_LIST.IMPORT.CONCURRENT_ERROR'),
            );
            return;
          }
          this.fileError.set(
            refusalReason(err) ?? this.translate.instant('BILLING.PRICE_LIST.IMPORT.FAILED'),
          );
        },
      });
  }
}
