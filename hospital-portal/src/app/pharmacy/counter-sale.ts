import { map } from 'rxjs';
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
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../core/toast.service';
import {
  InventoryItemResponse,
  PharmacyResponse,
  PharmacyService,
  StockLotResponse,
} from '../services/pharmacy.service';
import {
  CounterSaleLineRequest,
  CounterSaleMethod,
  CounterSaleRequest,
  CounterSaleResponse,
  PharmacySaleService,
} from '../services/pharmacy-sale.service';
import { allRows } from '../shared/all-pages';
import { localDateString, isServerTimeToday } from '../shared/date-utils';
import { FieldRefusal, refuseFields } from '../shared/forms/invalid-fields';
import {
  MOBILE_MONEY_OPERATORS,
  MobileMoneyOperator,
  containsCardNumber,
  mobileMoneyOperatorLabelKey,
} from '../shared/mobile-money-operator';
import {
  PAYMENT_OUTCOME_UNKNOWN_KEY,
  newPaymentRequestKey,
  paymentOutcomeUnknown,
  retryPaymentWithSameKey,
} from '../shared/payment-request-key';
import { CfaPipe } from '../shared/pipes/cfa.pipe';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { refusalReason } from '../shared/refusal-reason';
import { earlierLotWithStock, fefoFirstLot } from './fefo-lots';

/** How many matching items the picker lists at once (the search narrows the rest). */
const PICKER_LIMIT = 12;
/** The first page of the pharmacy's sales, newest first: today's are on it. */
const TODAY_PAGE_SIZE = 50;
const TODAY_SORT = ['saleDate,desc', 'createdAt,desc'] as const;

/** The till methods a counter sale takes (QA D1611: never INSURANCE). */
export const COUNTER_SALE_METHODS: readonly { value: CounterSaleMethod; labelKey: string }[] = [
  { value: 'CASH', labelKey: 'PORTAL.ENUM.PAYMENT_METHOD.CASH' },
  { value: 'MOBILE_MONEY', labelKey: 'PORTAL.ENUM.PAYMENT_METHOD.MOBILE_MONEY' },
];

/** The sale-level fields the form may refuse. */
const SALE_FIELD_IDS = [
  'salePharmacy',
  'saleItemSearch',
  'saleMethod',
  'saleOperator',
  'saleRef',
  'saleNotes',
];

/** The server's sale-level keys, and the control each one names. */
const SALE_FIELD_BY_KEY: Readonly<Record<string, string>> = {
  pharmacyId: 'salePharmacy',
  hospitalId: 'salePharmacy',
  lines: 'saleItemSearch',
  paymentMethod: 'saleMethod',
  mobileMoneyOperator: 'saleOperator',
  referenceNumber: 'saleRef',
  notes: 'saleNotes',
};

/** One basket line, as the pharmacist edits it. */
export interface BasketLine {
  /** Stable for the line's lifetime: its control ids are built from it. */
  key: number;
  item: InventoryItemResponse;
  stockLotId: string;
  quantity: number | null;
  /** QA D2674: the quantity counts whole packs (only for an item with `unitsPerPack`). */
  inPacks: boolean;
  fefoOverrideReason: string;
}

type LotsState = 'loading' | 'failed' | 'ready';

/**
 * QA D2309: the pharmacy's counter sale (OTC, walk-in, no prescription).
 *
 * The pharmacist builds a basket from the pharmacy's sellable stock (active,
 * not controlled, in stock), each line from its first-expired lot (QA D964:
 * a later lot needs a reason), and takes cash or mobile money. The server
 * prices every line from the list price (QA D2671), so no price is sent: the
 * total shown is a preview. One request key per open sale (QA D2430): a press
 * whose answer was lost is retried with the same key and never recorded twice.
 */
@Component({
  selector: 'app-counter-sale',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, CfaPipe, ServerTimePipe],
  templateUrl: './counter-sale.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './counter-sale.scss',
})
export class CounterSaleComponent implements OnInit {
  private readonly pharmacySvc = inject(PharmacyService);
  private readonly saleSvc = inject(PharmacySaleService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly methods = COUNTER_SALE_METHODS;
  readonly mobileMoneyOperators = MOBILE_MONEY_OPERATORS;
  readonly operatorLabelKey = mobileMoneyOperatorLabelKey;

  pharmacies = signal<PharmacyResponse[]>([]);
  pharmaciesLoaded = signal(false);
  pharmacyFailed = signal(false);
  selectedPharmacyId = '';

  inventoryItems = signal<InventoryItemResponse[]>([]);
  itemsLoading = signal(false);
  itemsFailed = signal(false);
  searchTerm = signal('');

  /** Every lot of each basket item (also the empty and expired ones: they say the item is kept in lots). */
  private readonly lotsByItem = signal<Record<string, StockLotResponse[]>>({});
  private readonly lotsState = signal<Record<string, LotsState>>({});

  lines = signal<BasketLine[]>([]);
  private nextLineKey = 1;

  paymentMethod: CounterSaleMethod = 'CASH';
  mobileMoneyOperator: MobileMoneyOperator | null = null;
  referenceNumber = '';
  notes = '';

  saving = signal(false);
  /** The server's reason for the last refusal, shown by the submit button. */
  serverError = signal<string | null>(null);
  receipt = signal<CounterSaleResponse | null>(null);
  /** QA D2430: one key per open sale form, resent on every press until « Nouvelle vente ». */
  clientRequestId: string | undefined = newPaymentRequestKey();

  todaySales = signal<CounterSaleResponse[]>([]);
  todayLoading = signal(false);
  todayFailed = signal(false);
  /** QA D1347: every row of the first page is today's and the server has more pages. */
  todayMore = signal(false);

  private scopeVersion = 0;

  /** Active, not controlled (QA D2309: the server refuses them at the counter), with stock. */
  readonly sellableItems = computed(() =>
    this.inventoryItems()
      .filter((i) => i.active !== false && !i.controlled && (i.quantityOnHand ?? 0) > 0)
      .sort((a, b) => this.itemName(a).localeCompare(this.itemName(b))),
  );

  readonly matchingItems = computed(() => {
    const query = this.searchTerm().trim().toLocaleLowerCase();
    return this.sellableItems().filter(
      (i) =>
        !query ||
        [i.medicationName, i.medicationCode].some((v) => v?.toLocaleLowerCase().includes(query)),
    );
  });

  readonly pickerItems = computed(() => this.matchingItems().slice(0, PICKER_LIMIT));

  ngOnInit(): void {
    this.loadPharmacies();
  }

  loadPharmacies(): void {
    this.pharmacyFailed.set(false);
    allRows((page, size) => this.pharmacySvc.listPharmacies(page, size)).subscribe({
      next: (list) => {
        this.pharmacies.set(list);
        this.pharmaciesLoaded.set(true);
        if (list.length > 0) {
          this.selectedPharmacyId = list[0].id;
          this.onPharmacyChange();
        }
      },
      error: () => this.pharmacyFailed.set(true),
    });
  }

  onPharmacyChange(): void {
    this.scopeVersion++;
    this.inventoryItems.set([]);
    this.lines.set([]);
    this.lotsByItem.set({});
    this.lotsState.set({});
    this.searchTerm.set('');
    this.serverError.set(null);
    this.todaySales.set([]);
    if (!this.selectedPharmacyId) return;
    this.loadItems();
    this.loadTodaySales();
  }

  loadItems(): void {
    const scope = this.scopeVersion;
    const pharmacyId = this.selectedPharmacyId;
    this.itemsLoading.set(true);
    this.itemsFailed.set(false);
    allRows((page, size) =>
      this.pharmacySvc.listInventoryByPharmacy(pharmacyId, page, size).pipe(map((r) => r?.data)),
    ).subscribe({
      next: (items) => {
        if (scope !== this.scopeVersion) return;
        this.inventoryItems.set(items);
        this.itemsLoading.set(false);
      },
      error: () => {
        if (scope !== this.scopeVersion) return;
        this.itemsFailed.set(true);
        this.itemsLoading.set(false);
      },
    });
  }

  loadTodaySales(): void {
    const scope = this.scopeVersion;
    this.todayLoading.set(true);
    this.todayFailed.set(false);
    this.todayMore.set(false);
    this.saleSvc.listByPharmacy(this.selectedPharmacyId, 0, TODAY_PAGE_SIZE, TODAY_SORT).subscribe({
      next: (page) => {
        if (scope !== this.scopeVersion) return;
        const rows = page?.content ?? [];
        const today = rows.filter((s) => isServerTimeToday(s.saleDate));
        this.todaySales.set(today);
        // Newest first: the next page can hold more of today's sales only when this one is all today's.
        this.todayMore.set(
          today.length > 0 && today.length === rows.length && (page?.totalPages ?? 0) > 1,
        );
        this.todayLoading.set(false);
      },
      error: () => {
        if (scope !== this.scopeVersion) return;
        this.todayFailed.set(true);
        this.todayLoading.set(false);
      },
    });
  }

  // ── Items and lots ──

  itemName(item: InventoryItemResponse): string {
    return (
      item.medicationName ?? item.medicationCode ?? this.translate.instant('PHARMACY.UNKNOWN_ITEM')
    );
  }

  hasPrice(item: InventoryItemResponse): boolean {
    return item.unitPrice !== null && item.unitPrice !== undefined;
  }

  /** Enter in the search adds the first match instead of submitting the sale. */
  onSearchEnter(event: Event): void {
    event.preventDefault();
    const first = this.pickerItems().find((i) => this.hasPrice(i));
    if (first) this.addItem(first);
  }

  addItem(item: InventoryItemResponse): void {
    if (!this.hasPrice(item)) return;
    const line: BasketLine = {
      key: this.nextLineKey++,
      item,
      stockLotId: '',
      quantity: 1,
      inPacks: false,
      fefoOverrideReason: '',
    };
    this.lines.update((list) => [...list, line]);
    this.serverError.set(null);
    if (this.lotsState()[item.id]) this.preselectLot(line);
    else this.loadLots(item.id);
  }

  removeLine(line: BasketLine): void {
    this.lines.update((list) => list.filter((l) => l.key !== line.key));
  }

  loadLots(itemId: string): void {
    const scope = this.scopeVersion;
    this.lotsState.update((s) => ({ ...s, [itemId]: 'loading' }));
    allRows((page, size) =>
      this.pharmacySvc.listLotsByInventoryItem(itemId, page, size).pipe(map((r) => r?.data)),
    ).subscribe({
      next: (lots) => {
        if (scope !== this.scopeVersion) return;
        this.lotsByItem.update((m) => ({ ...m, [itemId]: lots }));
        this.lotsState.update((s) => ({ ...s, [itemId]: 'ready' }));
        for (const line of this.lines()) {
          if (line.item.id === itemId && !line.stockLotId) this.preselectLot(line);
        }
      },
      error: () => {
        if (scope !== this.scopeVersion) return;
        this.lotsState.update((s) => ({ ...s, [itemId]: 'failed' }));
      },
    });
  }

  lotsLoading(line: BasketLine): boolean {
    return this.lotsState()[line.item.id] === 'loading';
  }

  lotsFailed(line: BasketLine): boolean {
    return this.lotsState()[line.item.id] === 'failed';
  }

  /** QA D2670: an item with any lot is sold from a lot (the server's rule). */
  keptInLots(line: BasketLine): boolean {
    return (this.lotsByItem()[line.item.id]?.length ?? 0) > 0;
  }

  /**
   * The lots this line may take, shortest-dated first: stock left, not expired
   * (QA D710: expired ON its expiry date), minus what the lines above it
   * already take from the same lot (the server counts them the same way).
   */
  lineLots(line: BasketLine): StockLotResponse[] {
    const today = localDateString();
    const taken = new Map<string, number>();
    for (const other of this.lines()) {
      if (other.key === line.key) break;
      if (other.item.id === line.item.id && other.stockLotId) {
        taken.set(other.stockLotId, (taken.get(other.stockLotId) ?? 0) + this.lineUnits(other));
      }
    }
    return (this.lotsByItem()[line.item.id] ?? [])
      .filter((lot) => !!lot.expiryDate && lot.expiryDate > today)
      .map((lot) => ({
        ...lot,
        remainingQuantity: lot.remainingQuantity - (taken.get(lot.id) ?? 0),
      }))
      .filter((lot) => lot.remainingQuantity > 0)
      .sort((a, b) => a.expiryDate.localeCompare(b.expiryDate));
  }

  chosenLot(line: BasketLine): StockLotResponse | undefined {
    return this.lineLots(line).find((lot) => lot.id === line.stockLotId);
  }

  private preselectLot(line: BasketLine): void {
    line.stockLotId = fefoFirstLot(this.lineLots(line))?.id ?? '';
    this.lines.update((list) => [...list]);
  }

  /** QA D964: a shorter-dated lot still holds stock, so taking this one needs a reason. */
  earlierLot(line: BasketLine): StockLotResponse | undefined {
    return earlierLotWithStock(this.lineLots(line), this.chosenLot(line));
  }

  onLotChange(line: BasketLine): void {
    if (!this.earlierLot(line)) line.fefoOverrideReason = '';
  }

  /** QA D2674: only an item counted in units with a pack size can be sold by the pack. */
  packable(item: InventoryItemResponse): boolean {
    return (item.unitsPerPack ?? 0) > 1;
  }

  lineUnits(line: BasketLine): number {
    const qty = line.quantity ?? 0;
    return line.inPacks && this.packable(line.item) ? qty * (line.item.unitsPerPack ?? 1) : qty;
  }

  /** Preview only: the server prices the sale from the same list price. */
  lineTotal(line: BasketLine): number {
    return this.lineUnits(line) * (line.item.unitPrice ?? 0);
  }

  total(): number {
    return this.lines().reduce((sum, l) => sum + this.lineTotal(l), 0);
  }

  /** The chosen lot holds less than the line asks: a second line takes the rest from the next lot. */
  lotShort(line: BasketLine): StockLotResponse | undefined {
    const lot = this.chosenLot(line);
    return lot && this.lineUnits(line) > lot.remainingQuantity ? lot : undefined;
  }

  lineQtyId(line: BasketLine): string {
    return `saleQty-${line.key}`;
  }

  lineLotId(line: BasketLine): string {
    return `saleLot-${line.key}`;
  }

  lineFefoId(line: BasketLine): string {
    return `saleFefo-${line.key}`;
  }

  private allFieldIds(): string[] {
    return [
      ...SALE_FIELD_IDS,
      ...this.lines().flatMap((l) => [this.lineQtyId(l), this.lineLotId(l), this.lineFefoId(l)]),
    ];
  }

  // ── Submit ──

  submit(): void {
    if (this.saving()) return;
    this.serverError.set(null);
    const refusals = this.clientRefusals();
    const root = this.host.nativeElement;
    if (refusals.length > 0) {
      refuseFields(root, refusals, this.allFieldIds());
      this.toast.error(refusals.map((r) => r.message).join(' '));
      return;
    }
    refuseFields(root, [], this.allFieldIds());

    // The form is shown only once a pharmacy is selected; the server checks the
    // pharmacy and the active hospital itself (pharmacy.sale.hospitalMismatch).
    const pharmacy = this.pharmacies().find((p) => p.id === this.selectedPharmacyId);
    if (!pharmacy) return;
    const hospitalId = this.auth.getHospitalId() ?? pharmacy.hospitalId;

    const mobileMoney = this.paymentMethod === 'MOBILE_MONEY';
    const req: CounterSaleRequest = {
      pharmacyId: pharmacy.id,
      hospitalId,
      paymentMethod: this.paymentMethod,
      currency: 'XOF',
      ...(mobileMoney && this.mobileMoneyOperator
        ? { mobileMoneyOperator: this.mobileMoneyOperator }
        : {}),
      ...(mobileMoney && this.referenceNumber.trim()
        ? { referenceNumber: this.referenceNumber.trim() }
        : {}),
      ...(this.notes.trim() ? { notes: this.notes.trim() } : {}),
      ...(this.clientRequestId ? { clientRequestId: this.clientRequestId } : {}),
      lines: this.lines().map((line) => this.lineRequest(line)),
    };

    this.saving.set(true);
    this.saleSvc
      .createSale(req)
      .pipe(retryPaymentWithSameKey(this.clientRequestId))
      .subscribe({
        next: (sale) => {
          this.saving.set(false);
          this.receipt.set(sale);
          this.toast.success(this.translate.instant('PHARMACY_SALE.SAVED'));
          this.lotsByItem.set({});
          this.lotsState.set({});
          this.loadItems();
          this.loadTodaySales();
        },
        error: (err) => {
          this.saving.set(false);
          if (paymentOutcomeUnknown(err)) {
            // QA D2430: no answer is not a failure; the same key records nothing twice.
            const message = this.translate.instant(PAYMENT_OUTCOME_UNKNOWN_KEY);
            this.serverError.set(message);
            this.toast.warning(message);
          } else {
            const message = refusalReason(err) ?? this.translate.instant('PHARMACY_SALE.FAILED');
            this.serverError.set(message);
            this.toast.error(message);
            this.markServerRefusal(err);
          }
        },
      });
  }

  private lineRequest(line: BasketLine): CounterSaleLineRequest {
    const reason = this.earlierLot(line) ? line.fefoOverrideReason.trim() : '';
    return {
      medicationCatalogItemId: line.item.medicationCatalogItemId,
      ...(line.stockLotId ? { stockLotId: line.stockLotId } : {}),
      quantity: line.quantity ?? 0,
      ...(line.inPacks && this.packable(line.item) ? { inPacks: true } : {}),
      ...(reason ? { fefoOverrideReason: reason } : {}),
    };
  }

  /** Every field the form refuses before anything is sent, each with its own reason. */
  private clientRefusals(): FieldRefusal[] {
    const t = (key: string, params?: object) => this.translate.instant(key, params);
    const refusals: FieldRefusal[] = [];
    if (this.lines().length === 0) {
      refusals.push({ id: 'saleItemSearch', message: t('PHARMACY_SALE.BASKET_REQUIRED') });
    }
    for (const line of this.lines()) {
      const qty = line.quantity;
      if (qty === null || qty === undefined || !(qty > 0)) {
        refusals.push({ id: this.lineQtyId(line), message: t('PHARMACY_SALE.QUANTITY_POSITIVE') });
      } else if (line.inPacks && !Number.isInteger(qty)) {
        refusals.push({ id: this.lineQtyId(line), message: t('PHARMACY_SALE.PACKS_WHOLE') });
      }
      if (this.lotsLoading(line)) {
        refusals.push({ id: this.lineLotId(line), message: t('PHARMACY_SALE.LOTS_LOADING') });
      } else if (this.lotsFailed(line)) {
        refusals.push({ id: this.lineLotId(line), message: t('PHARMACY_SALE.LOTS_FAILED') });
      } else if (this.keptInLots(line) && !this.chosenLot(line)) {
        refusals.push({
          id: this.lineLotId(line),
          message: t(
            this.lineLots(line).length === 0
              ? 'PHARMACY_SALE.NO_USABLE_LOT'
              : 'PHARMACY_SALE.LOT_REQUIRED',
          ),
        });
      } else if (this.earlierLot(line) && !line.fefoOverrideReason.trim()) {
        refusals.push({
          id: this.lineFefoId(line),
          message: t('PHARMACY_SALE.FEFO_REASON_REQUIRED'),
        });
      }
    }
    const mobileMoney = this.paymentMethod === 'MOBILE_MONEY';
    if (mobileMoney && !this.mobileMoneyOperator) {
      refusals.push({ id: 'saleOperator', message: t('MOBILE_MONEY.OPERATOR_REQUIRED') });
    }
    if (mobileMoney && !this.referenceNumber.trim()) {
      refusals.push({ id: 'saleRef', message: t('PHARMACY_SALE.REFERENCE_REQUIRED') });
    } else if (mobileMoney && containsCardNumber(this.referenceNumber)) {
      // Owner decision D-15: never a card number (the server refuses it too).
      refusals.push({ id: 'saleRef', message: t('MOBILE_MONEY.CARD_NUMBER_REFUSED') });
    }
    if (containsCardNumber(this.notes)) {
      refusals.push({ id: 'saleNotes', message: t('MOBILE_MONEY.CARD_NUMBER_REFUSED') });
    }
    return refusals;
  }

  /**
   * Marks the fields the server named. A line's field is named with its index
   * by bean validation (`lines[1].quantity`); a business refusal names the
   * bare field (`stockLotId`), which points at a line only when there is one.
   */
  private markServerRefusal(err: unknown): void {
    const body = (err as { error?: unknown } | null)?.error as
      { fieldErrors?: unknown } | null | undefined;
    const fieldErrors = body?.fieldErrors;
    if (!fieldErrors || typeof fieldErrors !== 'object') return;
    const lines = this.lines();
    const refusals: FieldRefusal[] = [];
    for (const [key, text] of Object.entries(fieldErrors as Record<string, unknown>)) {
      if (typeof text !== 'string' || !text.trim()) continue;
      const saleField = SALE_FIELD_BY_KEY[key];
      if (saleField) {
        refusals.push({ id: saleField, message: text });
        continue;
      }
      const indexed = /^lines\[(\d+)\]\.(\w+)$/.exec(key);
      let line: BasketLine | undefined;
      if (indexed) line = lines[Number(indexed[1])];
      else if (lines.length === 1) line = lines[0];
      if (!line) continue;
      refusals.push({ id: this.lineFieldId(line, indexed ? indexed[2] : key), message: text });
    }
    if (refusals.length > 0) refuseFields(this.host.nativeElement, refusals, this.allFieldIds());
  }

  /** The control of `line` a server line key names (the quantity for the item and price keys). */
  private lineFieldId(line: BasketLine, field: string): string {
    if (field === 'stockLotId') return this.lineLotId(line);
    if (field === 'fefoOverrideReason') {
      return this.earlierLot(line) ? this.lineFefoId(line) : this.lineLotId(line);
    }
    return this.lineQtyId(line);
  }

  /** « Nouvelle vente »: an empty basket and a new request key. */
  newSale(): void {
    this.receipt.set(null);
    this.lines.set([]);
    this.paymentMethod = 'CASH';
    this.mobileMoneyOperator = null;
    this.referenceNumber = '';
    this.notes = '';
    this.searchTerm.set('');
    this.serverError.set(null);
    this.clientRequestId = newPaymentRequestKey();
  }

  methodLabelKey(method: string | null | undefined): string {
    return (
      this.methods.find((m) => m.value === method)?.labelKey ?? 'PORTAL.ENUM.PAYMENT_METHOD.OTHER'
    );
  }

  saleItems(sale: CounterSaleResponse): string {
    return (sale.lines ?? [])
      .map((l) => l.medicationName ?? this.translate.instant('PHARMACY.UNKNOWN_ITEM'))
      .join(', ');
  }
}
