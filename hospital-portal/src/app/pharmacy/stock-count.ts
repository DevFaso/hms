import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Observable, catchError, concatMap, forkJoin, from, map, of, toArray } from 'rxjs';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { actionRoles } from '../core/action-permissions';
import {
  InventoryItemResponse,
  PharmacyService,
  StockLotResponse,
  StockWitnessCandidate,
} from '../services/pharmacy.service';
import {
  StockBalanceDrift,
  StockCountResponse,
  StockCountService,
  StockCountStatus,
  StockStore,
} from '../services/stock-count.service';
import { allRows } from '../shared/all-pages';
import { refusalReason, refusedFieldReasons } from '../shared/refusal-reason';
import { refuseFields } from '../shared/forms/invalid-fields';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';
import { ReasonDialogComponent } from '../shared/reason-dialog/reason-dialog.component';
import { FocusOnRenderDirective } from '../shared/a11y/focus-on-render.directive';
import { InlineSubFormDirective } from '../shared/a11y/inline-sub-form.directive';
import { InlineDiscardAskComponent } from '../shared/a11y/inline-discard-ask.component';
import { FormControlStyleDirective } from '../shared/forms/form-control-style.directive';
import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { CountUnitPipe } from '../shared/pipes/count-unit.pipe';
import { DashPipe } from '../shared/pipes/dash.pipe';
import { DateTimePipe } from '../shared/pipes/date-time.pipe';
import { KeyLabelPipe } from '../shared/pipes/key-label.pipe';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { pluralKey } from '../shared/i18n/plural-key';
import { WitnessConfirmComponent } from './witness-confirm/witness-confirm.component';

/** One line of the count sheet: a lot, or an item that keeps no lot. */
export interface CountSheetLine {
  key: string;
  inventoryItemId: string;
  stockLotId: string | null;
  medication: string;
  form: string | null;
  lotNumber: string | null;
  expiryDate: string | null;
  unit: string | null;
  book: number;
  itemRevision: number;
  lotRevision: number | null;
  /** One id per observation: a retry of the same line records it once. */
  clientRequestId: string;
  counted: number | null;
  state: 'idle' | 'saving' | 'saved' | 'refused';
  message: string | null;
}

type Tab = 'sheet' | 'counts' | 'balance';
type CountFilter = 'OPEN' | StockCountStatus | 'ALL';

const SHEET_FIELDS = ['countReason'];
const BALANCE_FIELDS = ['balanceReason', 'balanceConfirmed', 'balanceWitness'];
const CONFIRM_FIELDS = ['countConfirmed', 'countWitness'];

function newRequestId(): string {
  return crypto.randomUUID();
}

/**
 * QA D4183 (r81): the physical inventory of a store. The API of #1344 recorded counts, confirmed them, repaired a
 * drifted balance, and no menu, tab or button reached any of it.
 *
 * <ul>
 *   <li><strong>Comptage</strong>: start a count (its reason), type the counted quantity of each lot beside its
 *       book quantity, save. Saving records observations; nothing moves in the stock.</li>
 *   <li><strong>Comptages enregistrés</strong>: each count with its variance and status. A second person
 *       confirms the adjustment (a witness for a controlled drug); a count that will not be applied is closed
 *       with a reason (QA D4191), or counted again when the stock moved since.</li>
 *   <li><strong>Rapprochement des soldes</strong>: the items whose total no longer equals their lots, repaired
 *       from the lots as physically counted.</li>
 * </ul>
 * Every write is offered to the roles its endpoint admits (action-permissions.ts); the accountant reads.
 */
import { CatalogFormPipe } from './catalog-label';
@Component({
  selector: 'app-stock-count',
  standalone: true,
  imports: [
    CatalogFormPipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    CountUnitPipe,
    DashPipe,
    DateTimePipe,
    KeyLabelPipe,
    ServerTimePipe,
    FocusOnRenderDirective,
    InlineSubFormDirective,
    InlineDiscardAskComponent,
    FormControlStyleDirective,
    SubmitGuardDirective,
    ReasonDialogComponent,
    WitnessConfirmComponent,
  ],
  templateUrl: './stock-count.html',
  styleUrl: './stock-count.scss',
  changeDetection: ChangeDetectionStrategy.Eager,
})
export class StockCountComponent implements OnInit {
  private readonly api = inject(StockCountService);
  private readonly pharmacy = inject(PharmacyService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly roleContext = inject(RoleContextService);
  private readonly confirm = inject(ConfirmService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly stores = signal<StockStore[]>([]);
  readonly storesLoaded = signal(false);
  readonly storesFailed = signal(false);
  selectedStoreId = '';
  private scope = 0;

  readonly tab = signal<Tab>('counts');

  /** The roles each write endpoint admits; the page reads for the others (the accountant). */
  readonly canCount = computed(() =>
    this.roleContext.hasAnyActiveRoleExactly(actionRoles('stockCountRecord')),
  );
  readonly canReconcile = computed(() =>
    this.roleContext.hasAnyActiveRoleExactly(actionRoles('stockBalanceReconcile')),
  );

  // ── The count sheet ──
  readonly sheet = signal<CountSheetLine[] | null>(null);
  readonly sheetLoading = signal(false);
  readonly sheetFailed = signal(false);
  readonly sheetSaving = signal(false);
  /** Why this count is made; kept on every observation. */
  countReason = '';
  /** The reason the open sheet was started with. */
  readonly sheetReason = signal('');
  sheetSearch = '';
  showEmptyLots = false;

  // ── The recorded counts ──
  readonly counts = signal<StockCountResponse[]>([]);
  readonly countsLoading = signal(false);
  readonly countsFailed = signal(false);
  countFilter: CountFilter = 'OPEN';
  countSearch = '';
  readonly countFilters: CountFilter[] = [
    'OPEN',
    'PENDING',
    'OUTDATED',
    'APPLIED',
    'CLOSED',
    'ALL',
  ];
  /** The count whose adjustment is being confirmed. */
  readonly confirming = signal<StockCountResponse | null>(null);
  readonly confirmSaving = signal(false);
  confirmChecked = false;
  confirmWitness: string | undefined = undefined;
  /** The count being closed: the shared reason dialog is open on it. */
  readonly closing = signal<StockCountResponse | null>(null);
  readonly closeSaving = signal(false);
  readonly witnessCandidates = signal<StockWitnessCandidate[]>([]);

  // ── The balance reconciliation ──
  readonly drift = signal<StockBalanceDrift[]>([]);
  readonly driftLoading = signal(false);
  readonly driftFailed = signal(false);
  readonly reconciling = signal<StockBalanceDrift | null>(null);
  readonly reconcileSaving = signal(false);
  /** The counted quantity typed for each lot of the item being reconciled. */
  lotCounts: Record<string, number | null> = {};
  balanceReason = '';
  balanceConfirmed = false;
  balanceWitness: string | undefined = undefined;

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
        this.loadTab();
      },
      error: () => {
        this.storesLoaded.set(true);
        this.storesFailed.set(true);
      },
    });
  }

  /** Changing store leaves the open sheet: asked first when it holds typed counts. */
  async onStoreChange(next: string): Promise<void> {
    const previous = this.selectedStoreId;
    if (next === previous) return;
    if (this.unsavedLines().length > 0 && !(await this.askDiscardSheet())) {
      // The select shows the store the sheet belongs to again.
      this.selectedStoreId = next;
      queueMicrotask(() => (this.selectedStoreId = previous));
      return;
    }
    this.selectedStoreId = next;
    this.scope++;
    this.sheet.set(null);
    this.counts.set([]);
    this.drift.set([]);
    this.confirming.set(null);
    this.reconciling.set(null);
    this.loadTab();
  }

  setTab(tab: Tab): void {
    this.tab.set(tab);
    this.loadTab();
  }

  private loadTab(): void {
    if (!this.selectedStoreId) return;
    if (this.tab() === 'counts') this.loadCounts();
    else if (this.tab() === 'balance') this.loadDrift();
  }

  // ───────────────────────────── the count sheet ─────────────────────────────

  /** Starts a count: its reason, then the store's lots with their book quantities as they are now. */
  startCount(): void {
    const reason = this.countReason.trim();
    if (!reason || reason.length > 300) {
      const message = this.translate.instant('STOCK_COUNT.SHEET.REASON_REQUIRED');
      refuseFields(this.host.nativeElement, SHEET_FIELDS, SHEET_FIELDS, message);
      this.toast.error(message);
      return;
    }
    refuseFields(this.host.nativeElement, [], SHEET_FIELDS, '');
    this.sheetReason.set(reason);
    this.loadSheet([]);
  }

  /** Reads the book quantities again, keeping what was typed for the lines that have not moved. */
  refreshSheet(): void {
    this.loadSheet(this.sheet() ?? []);
  }

  private loadSheet(previous: CountSheetLine[]): void {
    const scope = this.scope;
    const storeId = this.selectedStoreId;
    this.sheetLoading.set(true);
    this.sheetFailed.set(false);
    const items$: Observable<InventoryItemResponse[]> = allRows((page, size) =>
      this.pharmacy.listInventoryByPharmacy(storeId, page, size).pipe(map((r) => r?.data)),
    );
    const lots$: Observable<StockLotResponse[]> = allRows((page, size) =>
      this.pharmacy.listLotsByPharmacy(storeId, page, size).pipe(map((r) => r?.data)),
    );
    forkJoin([items$, lots$]).subscribe({
      next: ([items, lots]) => {
        if (scope !== this.scope) return;
        this.sheet.set(this.buildSheet(items, lots, previous));
        this.sheetLoading.set(false);
      },
      error: () => {
        if (scope !== this.scope) return;
        this.sheetLoading.set(false);
        this.sheetFailed.set(true);
      },
    });
  }

  private buildSheet(
    items: InventoryItemResponse[],
    lots: StockLotResponse[],
    previous: CountSheetLine[],
  ): CountSheetLine[] {
    const before = new Map(previous.map((line) => [line.key, line]));
    const lotsByItem = new Map<string, StockLotResponse[]>();
    for (const lot of lots) {
      const list = lotsByItem.get(lot.inventoryItemId) ?? [];
      list.push(lot);
      lotsByItem.set(lot.inventoryItemId, list);
    }
    const lines: CountSheetLine[] = [];
    const line = (item: InventoryItemResponse, lot: StockLotResponse | null): CountSheetLine => {
      const key = lot ? `lot:${lot.id}` : `item:${item.id}`;
      const fresh: CountSheetLine = {
        key,
        inventoryItemId: item.id,
        stockLotId: lot?.id ?? null,
        medication:
          lot?.productName ??
          item.medicationName ??
          item.medicationCode ??
          this.translate.instant('PHARMACY.UNKNOWN_ITEM'),
        form: lot?.form ?? null,
        lotNumber: lot?.lotNumber ?? null,
        expiryDate: lot?.expiryDate ?? null,
        unit: item.unit && item.unit !== 'UNIT' ? item.unit : null,
        book: Number(lot ? lot.remainingQuantity : item.quantityOnHand) || 0,
        itemRevision: item.stockRevision ?? 0,
        lotRevision: lot ? (lot.stockRevision ?? 0) : null,
        clientRequestId: newRequestId(),
        counted: null,
        state: 'idle',
        message: null,
      };
      const old = before.get(key);
      if (!old) return fresh;
      const unmoved =
        old.book === fresh.book &&
        old.itemRevision === fresh.itemRevision &&
        old.lotRevision === fresh.lotRevision &&
        old.unit === fresh.unit;
      if (old.state === 'saved') {
        // Recorded: the line stays as saved, on the quantities it was recorded with.
        return { ...old };
      }
      // A line that moved is a new observation of the new book quantity; what was typed stays to be checked.
      return {
        ...fresh,
        counted: old.counted,
        clientRequestId: unmoved ? old.clientRequestId : fresh.clientRequestId,
        state: unmoved ? old.state : 'idle',
        message: unmoved ? old.message : null,
      };
    };
    for (const item of items.filter((i) => i.active)) {
      const itemLots = (lotsByItem.get(item.id) ?? []).sort((a, b) =>
        (a.expiryDate ?? '').localeCompare(b.expiryDate ?? ''),
      );
      if (itemLots.length === 0) lines.push(line(item, null));
      else for (const lot of itemLots) lines.push(line(item, lot));
    }
    return lines.sort(
      (a, b) =>
        a.medication.localeCompare(b.medication) ||
        (a.expiryDate ?? '').localeCompare(b.expiryDate ?? ''),
    );
  }

  /** The lines shown: the search, and the empty lots only when asked for (or already typed). */
  visibleSheet(): CountSheetLine[] {
    const query = this.sheetSearch.trim().toLocaleLowerCase();
    return (this.sheet() ?? []).filter(
      (line) =>
        (this.showEmptyLots ||
          line.book !== 0 ||
          line.stockLotId === null ||
          line.counted !== null) &&
        (!query ||
          line.medication.toLocaleLowerCase().includes(query) ||
          (line.lotNumber ?? '').toLocaleLowerCase().includes(query)),
    );
  }

  hiddenEmptyLots(): number {
    return (this.sheet() ?? []).filter(
      (line) => line.book === 0 && line.stockLotId !== null && line.counted === null,
    ).length;
  }

  typed(line: CountSheetLine): boolean {
    return line.counted !== null && (line.counted as unknown) !== '';
  }

  variance(line: CountSheetLine): number | null {
    return this.typed(line) ? Number(line.counted) - line.book : null;
  }

  /** The lines typed and not recorded yet. */
  unsavedLines(): CountSheetLine[] {
    return (this.sheet() ?? []).filter((line) => this.typed(line) && line.state !== 'saved');
  }

  savedLines(): number {
    return (this.sheet() ?? []).filter((line) => line.state === 'saved').length;
  }

  onCounted(line: CountSheetLine): void {
    if (line.state === 'refused') {
      line.state = 'idle';
      line.message = null;
    }
  }

  /** Records every typed line, one request each, in order; a refused line says why and the others go on. */
  saveSheet(): void {
    const lines = this.unsavedLines();
    if (lines.length === 0) {
      // The first line still to count is marked: a refusal is never a message alone.
      const message = this.translate.instant('STOCK_COUNT.SHEET.NOTHING_TYPED');
      const open = this.visibleSheet().filter((line) => line.state !== 'saved');
      refuseFields(
        this.host.nativeElement,
        open.slice(0, 1).map((line) => this.fieldId(line)),
        open.map((line) => this.fieldId(line)),
        message,
      );
      this.toast.error(message);
      return;
    }
    const invalid = lines.filter((line) => !this.validCount(line));
    if (invalid.length > 0) {
      const message = this.translate.instant('STOCK_COUNT.SHEET.INVALID_QUANTITY');
      for (const line of invalid) {
        line.state = 'refused';
        line.message = message;
      }
      refuseFields(
        this.host.nativeElement,
        invalid.map((line) => this.fieldId(line)),
        lines.map((line) => this.fieldId(line)),
        message,
      );
      this.toast.error(message);
      return;
    }
    this.sheetSaving.set(true);
    const reason = this.sheetReason();
    from(lines)
      .pipe(
        concatMap((line) => {
          line.state = 'saving';
          return this.api
            .record({
              inventoryItemId: line.inventoryItemId,
              stockLotId: line.stockLotId,
              clientRequestId: line.clientRequestId,
              expectedBookQuantity: line.book,
              expectedUnit: line.unit ?? 'UNIT',
              expectedItemRevision: line.itemRevision,
              expectedLotRevision: line.lotRevision,
              countedQuantity: Number(line.counted),
              reason,
            })
            .pipe(
              map(() => {
                line.state = 'saved';
                line.message = null;
                return true;
              }),
              catchError((err: unknown) => {
                line.state = 'refused';
                line.message =
                  refusalReason(err) ?? this.translate.instant('STOCK_COUNT.SHEET.LINE_FAILED');
                return of(false);
              }),
            );
        }),
        toArray(),
      )
      .subscribe((results) => {
        this.sheetSaving.set(false);
        this.sheet.update((rows) => (rows ? [...rows] : rows));
        // Each refused line is marked with the server's own reason, under its field.
        const refusedLines = lines.filter((line) => line.state === 'refused' && line.message);
        refuseFields(
          this.host.nativeElement,
          refusedLines.map((line) => ({ id: this.fieldId(line), message: line.message ?? '' })),
          lines.map((line) => this.fieldId(line)),
        );
        const saved = results.filter(Boolean).length;
        const refused = results.length - saved;
        if (saved > 0) {
          this.toast.success(
            this.translate.instant(pluralKey('STOCK_COUNT.SHEET.SAVED', saved), { count: saved }),
          );
        }
        if (refused > 0) {
          this.toast.error(
            this.translate.instant(pluralKey('STOCK_COUNT.SHEET.REFUSED', refused), {
              count: refused,
            }),
          );
        }
      });
  }

  private validCount(line: CountSheetLine): boolean {
    const n = Number(line.counted);
    return Number.isFinite(n) && n >= 0 && Math.round(n * 100) === n * 100;
  }

  fieldId(line: CountSheetLine): string {
    return `counted-${line.key.replace(':', '-')}`;
  }

  /** Leaves the sheet; asked first when typed counts are not recorded. */
  async closeSheet(): Promise<void> {
    if (this.unsavedLines().length > 0 && !(await this.askDiscardSheet())) return;
    this.sheet.set(null);
    this.countReason = '';
    this.sheetSearch = '';
    this.setTab('counts');
  }

  private askDiscardSheet(): Promise<boolean> {
    const count = this.unsavedLines().length;
    return this.confirm.ask({
      titleKey: 'STOCK_COUNT.SHEET.DISCARD_TITLE',
      messageKey: pluralKey('STOCK_COUNT.SHEET.DISCARD_MESSAGE', count),
      params: { count },
      confirmKey: 'STOCK_COUNT.SHEET.DISCARD_CONFIRM',
      dismissKey: 'COMMON.BACK',
    });
  }

  // ───────────────────────────── the recorded counts ─────────────────────────────

  loadCounts(): void {
    const scope = this.scope;
    const storeId = this.selectedStoreId;
    if (!storeId) return;
    this.countsLoading.set(true);
    this.countsFailed.set(false);
    allRows((page, size) => this.api.list(storeId, page, size)).subscribe({
      next: (rows) => {
        if (scope !== this.scope) return;
        this.counts.set(rows);
        this.countsLoading.set(false);
      },
      error: () => {
        if (scope !== this.scope) return;
        this.countsLoading.set(false);
        this.countsFailed.set(true);
      },
    });
  }

  visibleCounts(): StockCountResponse[] {
    const query = this.countSearch.trim().toLocaleLowerCase();
    return this.counts().filter(
      (count) =>
        this.matchesFilter(count.status) &&
        (!query ||
          (count.medicationName ?? '').toLocaleLowerCase().includes(query) ||
          (count.lotNumber ?? '').toLocaleLowerCase().includes(query)),
    );
  }

  private matchesFilter(status: StockCountStatus): boolean {
    if (this.countFilter === 'ALL') return true;
    if (this.countFilter === 'OPEN') return status === 'PENDING' || status === 'OUTDATED';
    return status === this.countFilter;
  }

  countOf(status: StockCountStatus): number {
    return this.counts().filter((count) => count.status === status).length;
  }

  signed(value: number): string {
    return value > 0 ? `+${value}` : String(value);
  }

  /** What the row's main button does: count again, or confirm (said plainly when nothing will move). */
  primaryActionKey(count: StockCountResponse): string {
    if (count.status === 'OUTDATED') return 'STOCK_COUNT.RECOUNT';
    return Number(count.variance) === 0
      ? 'STOCK_COUNT.CONFIRM.ACTION_NO_VARIANCE'
      : 'STOCK_COUNT.CONFIRM.ACTION';
  }

  openConfirm(count: StockCountResponse): void {
    this.closing.set(null);
    this.confirming.set(count);
    this.confirmChecked = false;
    this.confirmWitness = undefined;
    if (this.needsWitness(count)) this.loadWitnessCandidates();
  }

  /** A variance of a controlled substance is adjusted before a witness. */
  needsWitness(count: StockCountResponse): boolean {
    return count.controlled && Number(count.variance) !== 0;
  }

  /** The adjustment is confirmed explicitly; the server refuses the recorder when a colleague exists. */
  confirmAdjustment(): void {
    const count = this.confirming();
    if (!count) return;
    const missing = [
      ...(this.confirmChecked ? [] : ['countConfirmed']),
      ...(this.needsWitness(count) && !this.confirmWitness ? ['countWitness'] : []),
    ];
    if (missing.length > 0) {
      const message = this.translate.instant(
        missing.includes('countConfirmed')
          ? 'STOCK_COUNT.CONFIRM.CHECK_REQUIRED'
          : 'PHARMACY.WITNESS_REQUIRED',
      );
      refuseFields(this.host.nativeElement, missing, CONFIRM_FIELDS, message);
      this.toast.error(message);
      return;
    }
    refuseFields(this.host.nativeElement, [], CONFIRM_FIELDS, '');
    this.confirmSaving.set(true);
    this.api.confirm(count.id, this.needsWitness(count) ? this.confirmWitness : null).subscribe({
      next: (saved) => {
        this.confirmSaving.set(false);
        this.confirming.set(null);
        this.replace(saved);
        this.toast.success(
          this.translate.instant(
            Number(saved.variance) === 0
              ? 'STOCK_COUNT.CONFIRM.DONE_NO_VARIANCE'
              : 'STOCK_COUNT.CONFIRM.DONE',
          ),
        );
        // An adjustment moves the stock: the other counts of the item may now be outdated.
        this.loadCounts();
      },
      error: (err: unknown) => {
        this.confirmSaving.set(false);
        // A 400 that names the confirmation or the witness marks that field.
        this.markRefused(err, { confirmed: 'countConfirmed', witnessedBy: 'countWitness' });
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('STOCK_COUNT.CONFIRM.FAILED'),
        );
        this.loadCounts();
      },
    });
  }

  startClose(count: StockCountResponse): void {
    this.confirming.set(null);
    this.closing.set(count);
  }

  /** QA D4191: the count leaves the waiting list, with who closed it and why. */
  closeCount(count: StockCountResponse, reason: string): void {
    this.closeSaving.set(true);
    this.api.close(count.id, reason).subscribe({
      next: (saved) => {
        this.closeSaving.set(false);
        this.closing.set(null);
        this.replace(saved);
        this.toast.success(this.translate.instant('STOCK_COUNT.CLOSE.DONE'));
      },
      error: (err: unknown) => {
        this.closeSaving.set(false);
        this.toast.error(refusalReason(err) ?? this.translate.instant('STOCK_COUNT.CLOSE.FAILED'));
      },
    });
  }

  /** The stock moved since the count: a new sheet, on the medicine, with the same reason. */
  recount(count: StockCountResponse): void {
    this.countReason = count.reason;
    this.sheetReason.set(count.reason);
    this.sheetSearch = count.medicationName ?? '';
    this.tab.set('sheet');
    this.loadSheet(this.sheet() ?? []);
  }

  /** Marks the fields a server refusal names (`fieldErrors`), each with the server's reason. */
  private markRefused(err: unknown, idByField: Readonly<Record<string, string>>): void {
    const refused = refusedFieldReasons(err, idByField);
    if (refused.length === 0) return;
    refuseFields(
      this.host.nativeElement,
      refused.map(([id, message]) => ({ id, message })),
      Object.values(idByField),
    );
  }

  private replace(saved: StockCountResponse): void {
    this.counts.update((rows) => rows.map((row) => (row.id === saved.id ? saved : row)));
  }

  private loadWitnessCandidates(): void {
    this.pharmacy.listWitnessCandidates().subscribe({
      next: (res) => this.witnessCandidates.set(res?.data ?? []),
      error: () => this.witnessCandidates.set([]),
    });
  }

  // ───────────────────────────── the balance reconciliation ─────────────────────────────

  loadDrift(): void {
    const scope = this.scope;
    const storeId = this.selectedStoreId;
    if (!storeId) return;
    this.driftLoading.set(true);
    this.driftFailed.set(false);
    this.api.balanceDrift(storeId).subscribe({
      next: (rows) => {
        if (scope !== this.scope) return;
        this.drift.set(rows ?? []);
        this.driftLoading.set(false);
      },
      error: () => {
        if (scope !== this.scope) return;
        this.driftLoading.set(false);
        this.driftFailed.set(true);
      },
    });
  }

  openReconcile(item: StockBalanceDrift): void {
    this.reconciling.set(item);
    this.lotCounts = Object.fromEntries(item.lots.map((lot) => [lot.lotId, null]));
    this.balanceReason = '';
    this.balanceConfirmed = false;
    this.balanceWitness = undefined;
    if (item.controlled) this.loadWitnessCandidates();
  }

  lotFieldId(lotId: string): string {
    return `balance-lot-${lotId}`;
  }

  /** What was typed for the lots, summed; null until every lot has a quantity. */
  countedLotTotal(item: StockBalanceDrift): number | null {
    let total = 0;
    for (const lot of item.lots) {
      const value = this.lotCounts[lot.lotId];
      if (value === null || value === undefined || (value as unknown) === '') return null;
      total += Number(value);
    }
    return total;
  }

  /**
   * The total is brought to the lots as counted. The server takes the lots as evidence: each typed quantity
   * must be the lot's balance, else the lot itself is counted first (the count tab).
   */
  reconcile(): void {
    const item = this.reconciling();
    if (!item) return;
    const lotIds = item.lots.map((lot) => this.lotFieldId(lot.lotId));
    const all = [...lotIds, ...BALANCE_FIELDS];
    const blankLots = item.lots
      .filter((lot) => {
        const value = this.lotCounts[lot.lotId];
        return value === null || value === undefined || (value as unknown) === '' || value < 0;
      })
      .map((lot) => this.lotFieldId(lot.lotId));
    const missing = [
      ...blankLots,
      ...(this.balanceReason.trim() ? [] : ['balanceReason']),
      ...(this.balanceConfirmed ? [] : ['balanceConfirmed']),
      ...(item.controlled && !this.balanceWitness ? ['balanceWitness'] : []),
    ];
    if (missing.length > 0) {
      const message = this.translate.instant('STOCK_COUNT.BALANCE.INCOMPLETE');
      refuseFields(this.host.nativeElement, missing, all, message);
      this.toast.error(message);
      return;
    }
    const differing = item.lots.filter(
      (lot) => Number(this.lotCounts[lot.lotId]) !== Number(lot.remainingQuantity),
    );
    if (differing.length > 0) {
      const message = this.translate.instant('STOCK_COUNT.BALANCE.LOT_DIFFERS');
      refuseFields(
        this.host.nativeElement,
        differing.map((lot) => this.lotFieldId(lot.lotId)),
        all,
        message,
      );
      this.toast.error(message);
      return;
    }
    refuseFields(this.host.nativeElement, [], all, '');
    this.reconcileSaving.set(true);
    this.api
      .reconcileBalance(item.inventoryItemId, {
        expectedOnHand: item.quantityOnHand,
        expectedLotQuantity: item.lotQuantity,
        expectedItemRevision: item.stockRevision,
        confirmed: true,
        reason: this.balanceReason.trim(),
        witnessedBy: item.controlled ? this.balanceWitness : null,
        lotCounts: item.lots.map((lot) => ({
          lotId: lot.lotId,
          countedQuantity: Number(this.lotCounts[lot.lotId]),
        })),
      })
      .subscribe({
        next: () => {
          this.reconcileSaving.set(false);
          this.reconciling.set(null);
          this.toast.success(this.translate.instant('STOCK_COUNT.BALANCE.DONE'));
          this.loadDrift();
        },
        error: (err: unknown) => {
          this.reconcileSaving.set(false);
          this.markRefused(err, {
            reason: 'balanceReason',
            confirmed: 'balanceConfirmed',
            witnessedBy: 'balanceWitness',
          });
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('STOCK_COUNT.BALANCE.FAILED'),
          );
          this.loadDrift();
        },
      });
  }
}
