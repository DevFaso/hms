import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import {
  Component,
  computed,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { ToastService } from '../core/toast.service';
import {
  PharmacyService,
  PharmacyResponse,
  InventoryItemResponse,
  StockLotResponse,
  StockTransactionRequest,
  StockTransactionResponse,
  StockWitnessCandidate,
} from '../services/pharmacy.service';

/**
 * P-06: stock-adjustment uses a single generic form across five transaction types,
 * which lets users record an ADJUSTMENT without a reason code or a TRANSFER
 * without naming a destination. We render a type selector, then conditionally
 * surface only the fields that make sense for the chosen type and enforce
 * type-specific required fields client-side.
 *
 * This is a frontend-only refinement that reuses existing API fields. Backend
 * support for first-class supplier / PO / expiry / cost fields is a follow-up
 * (would require new StockTransactionRequest fields + a Liquibase migration).
 */
type TxType = 'RECEIPT' | 'DISPENSE' | 'ADJUSTMENT' | 'TRANSFER' | 'RETURN';

const ADJUSTMENT_REASON_CODES = [
  'DAMAGE',
  'EXPIRY_WRITE_OFF',
  'CYCLE_COUNT_VARIANCE',
  'CONTROLLED_DISCREPANCY',
] as const;

@Component({
  selector: 'app-stock-adjustment',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, EnumLabelPipe],
  templateUrl: './stock-adjustment.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './stock-adjustment.scss',
})
export class StockAdjustmentComponent implements OnInit {
  private readonly svc = inject(PharmacyService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  pharmacies = signal<PharmacyResponse[]>([]);
  /** True once the pharmacy list answered, so an empty list shows a hint, not a blank select (D59). */
  pharmaciesLoaded = signal(false);
  selectedPharmacyId = '';
  inventoryItems = signal<InventoryItemResponse[]>([]);

  // Recent transactions
  transactions = signal<StockTransactionResponse[]>([]);
  txLoading = signal(false);

  // Filter
  filterType = '';
  /** Every type, for the history filter (dispenses are listed, not entered here). */
  readonly transactionTypes: TxType[] = ['RECEIPT', 'DISPENSE', 'ADJUSTMENT', 'TRANSFER', 'RETURN'];
  /**
   * D82: the types this form may record. A dispense leaves stock through the
   * prescription dispense workflow only, and the server refuses it here.
   */
  readonly formTransactionTypes: TxType[] = ['RECEIPT', 'ADJUSTMENT', 'TRANSFER', 'RETURN'];
  /** D82: the selected item's lots; a lot-tracked item moves through one of them. */
  itemLots = signal<StockLotResponse[]>([]);
  readonly adjustmentReasonCodes = ADJUSTMENT_REASON_CODES;
  /** QA D157: colleagues who may witness a controlled-substance movement. */
  witnessCandidates = signal<StockWitnessCandidate[]>([]);

  // Form
  showForm = signal(false);
  saving = signal(false);
  form: StockTransactionRequest = this.emptyForm();

  // P-06: type-specific UI state. These collapse back into form fields
  // (referenceId, reason) on submit so the existing API contract is preserved.
  txType = signal<TxType | ''>('');
  adjustmentReasonCode = '';
  adjustmentNotes = '';
  receiptLotNumber = '';
  receiptSupplier = '';
  receiptPoReference = '';
  receiptExpiryDate = '';
  receiptCostPerUnit?: number;
  transferDestinationPharmacyId = '';
  returnReference = '';

  /** Convenience getters for the template's `@if` blocks. */
  readonly isReceipt = computed(() => this.txType() === 'RECEIPT');
  readonly isAdjustment = computed(() => this.txType() === 'ADJUSTMENT');
  readonly isTransfer = computed(() => this.txType() === 'TRANSFER');
  readonly isReturn = computed(() => this.txType() === 'RETURN');
  readonly isDispense = computed(() => this.txType() === 'DISPENSE');

  ngOnInit(): void {
    this.loadPharmacies();
  }

  private loadPharmacies(): void {
    this.svc.listPharmacies(0, 100).subscribe({
      next: (page) => {
        const list = page?.content ?? [];
        this.pharmacies.set(list);
        this.pharmaciesLoaded.set(true);
        if (list.length > 0) {
          this.selectedPharmacyId = list[0].id;
          this.onPharmacyChange();
        }
      },
    });
  }

  onPharmacyChange(): void {
    if (!this.selectedPharmacyId) return;
    this.loadInventoryItems();
    this.loadTransactions();
  }

  private loadInventoryItems(): void {
    this.svc.listInventoryByPharmacy(this.selectedPharmacyId, 0, 200).subscribe({
      next: (res) => {
        this.inventoryItems.set(res?.data?.content ?? []);
      },
    });
  }

  loadTransactions(): void {
    this.txLoading.set(true);
    const obs = this.filterType
      ? this.svc.listTransactionsByType(this.selectedPharmacyId, this.filterType)
      : this.svc.listTransactionsByPharmacy(this.selectedPharmacyId);

    obs.subscribe({
      next: (res) => {
        const page = res?.data;
        this.transactions.set(page?.content ?? []);
        this.txLoading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('PHARMACY.TX_LOAD_FAILED'));
        this.txLoading.set(false);
      },
    });
  }

  onFilterChange(): void {
    this.loadTransactions();
  }

  openForm(): void {
    this.resetTypedForm();
    this.form = this.emptyForm();
    this.itemLots.set([]);
    this.showForm.set(true);
    this.loadWitnessCandidates();
  }

  private loadWitnessCandidates(): void {
    this.svc.listWitnessCandidates().subscribe({
      next: (res) => this.witnessCandidates.set(res?.data ?? []),
      error: () => this.witnessCandidates.set([]),
    });
  }

  /** QA D157: the item chosen in the form. */
  selectedItem(): InventoryItemResponse | undefined {
    return this.inventoryItems().find((i) => i.id === this.form.inventoryItemId);
  }

  /** QA D157: a manual movement of a controlled substance (anything but a receipt) is witnessed. */
  needsWitness(): boolean {
    return !!this.txType() && !this.isReceipt() && !!this.selectedItem()?.controlled;
  }

  /**
   * QA D143: a quantity is positive, except an adjustment, which is signed
   * (a negative one writes stock off) but never zero.
   */
  quantityValid(): boolean {
    const q = Number(this.form.quantity);
    if (!Number.isFinite(q) || q === 0) return false;
    return this.isAdjustment() || q > 0;
  }

  closeForm(): void {
    this.showForm.set(false);
  }

  /** Reset all type-specific scratch fields when type changes or form opens. */
  private resetTypedForm(): void {
    this.adjustmentReasonCode = '';
    this.adjustmentNotes = '';
    this.receiptLotNumber = '';
    this.receiptSupplier = '';
    this.receiptPoReference = '';
    this.receiptExpiryDate = '';
    this.receiptCostPerUnit = undefined;
    this.transferDestinationPharmacyId = '';
    this.returnReference = '';
  }

  onItemChange(itemId: string): void {
    this.form.inventoryItemId = itemId;
    this.form.stockLotId = undefined;
    this.itemLots.set([]);
    if (!itemId) return;
    this.svc.listLotsByInventoryItem(itemId, 0, 100).subscribe({
      next: (res) => this.itemLots.set(res?.data?.content ?? []),
      error: () => this.itemLots.set([]),
    });
  }

  /** ISO date (YYYY-MM-DD) of today in the browser's calendar. */
  private today(): string {
    const d = new Date();
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    return `${d.getFullYear()}-${mm}-${dd}`;
  }

  onTypeChange(t: TxType | ''): void {
    this.txType.set(t);
    this.form.transactionType = t;
    this.resetTypedForm();
  }

  /** Pharmacies available as transfer destinations (excludes the source). */
  transferDestinationOptions(): PharmacyResponse[] {
    return this.pharmacies().filter((p) => p.id !== this.selectedPharmacyId);
  }

  /**
   * Validate type-specific required fields, then collapse them into the
   * existing StockTransactionRequest shape (`referenceId` / `reason`) before
   * sending to the backend.
   */
  submitForm(): void {
    if (!this.form.inventoryItemId || !this.txType()) {
      this.toast.error(this.translate.instant('PHARMACY.TX_REQUIRED_FIELDS'));
      return;
    }
    if (!this.quantityValid()) {
      this.toast.error(
        this.translate.instant(
          this.isAdjustment()
            ? 'PHARMACY.ADJUSTMENT_QUANTITY_NONZERO'
            : 'PHARMACY.TX_QUANTITY_POSITIVE',
        ),
      );
      return;
    }

    const validationErrorKey = this.validateTyped();
    if (validationErrorKey) {
      this.toast.error(this.translate.instant(validationErrorKey));
      return;
    }

    this.applyTypedFieldsToForm();

    this.saving.set(true);
    // D108: a goods receipt creates the lot (with its expiry) and counts it,
    // instead of raising the item total against a lot number nobody holds.
    const request$: Observable<unknown> = this.isReceipt()
      ? this.svc.receiveStock({
          inventoryItemId: this.form.inventoryItemId,
          lotNumber: this.receiptLotNumber.trim(),
          expiryDate: this.receiptExpiryDate,
          initialQuantity: this.form.quantity,
          supplier: this.receiptSupplier.trim() || undefined,
          unitCost: this.receiptCostPerUnit ?? undefined,
          receivedDate: this.today(),
          notes: this.receiptPoReference.trim() || undefined,
        })
      : this.svc.recordTransaction(this.form);
    request$.subscribe({
      next: () => {
        this.toast.success(this.translate.instant('PHARMACY.TX_RECORDED'));
        this.closeForm();
        this.saving.set(false);
        this.loadTransactions();
        this.loadInventoryItems();
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? this.translate.instant('PHARMACY.TX_RECORD_FAILED'),
        );
        this.saving.set(false);
      },
    });
  }

  /** Returns the i18n key of an error if a required type-specific field is missing. */
  private validateTyped(): string | null {
    if (this.isAdjustment()) {
      if (!this.adjustmentReasonCode) return 'PHARMACY.ADJUSTMENT_REASON_REQUIRED';
      if (!this.adjustmentNotes?.trim()) return 'PHARMACY.ADJUSTMENT_NOTES_REQUIRED';
    }
    if (this.isTransfer() && !this.transferDestinationPharmacyId) {
      return 'PHARMACY.TRANSFER_DESTINATION_REQUIRED';
    }
    if (this.isReturn() && !this.returnReference?.trim()) {
      return 'PHARMACY.RETURN_REFERENCE_REQUIRED';
    }
    if (this.isReceipt() && !this.receiptLotNumber?.trim()) {
      return 'PHARMACY.RECEIPT_LOT_REQUIRED';
    }
    if (this.isReceipt() && !this.receiptExpiryDate) {
      return 'PHARMACY.RECEIPT_EXPIRY_REQUIRED';
    }
    if (this.isReceipt() && this.receiptExpiryDate < this.today()) {
      return 'PHARMACY.RECEIPT_LOT_EXPIRED';
    }
    if (!this.isReceipt() && this.itemLots().length > 0 && !this.form.stockLotId) {
      return 'PHARMACY.LOT_REQUIRED_FOR_MOVEMENT';
    }
    if (this.needsWitness() && !this.form.witnessedBy) {
      return 'PHARMACY.WITNESS_REQUIRED';
    }
    return null;
  }

  /**
   * Apply type-specific fields to the request. As of FU-2, RECEIPT fields are
   * first-class on the backend (`lotNumber`, `supplier`, `poReference`,
   * `expiryDate`, `unitCost`). `referenceId` is a UUID-typed field on the
   * backend and is therefore only set when we genuinely have a UUID
   * (TRANSFER → destination pharmacy ID); never a free-text token.
   */
  private applyTypedFieldsToForm(): void {
    // Reset cross-type leakage
    this.form.lotNumber = undefined;
    this.form.supplier = undefined;
    this.form.poReference = undefined;
    this.form.expiryDate = undefined;
    this.form.unitCost = undefined;
    this.form.referenceId = undefined;
    if (!this.needsWitness()) {
      this.form.witnessedBy = undefined;
    }

    if (this.isReceipt()) {
      this.form.lotNumber = this.receiptLotNumber.trim();
      this.form.supplier = this.receiptSupplier.trim() || undefined;
      this.form.poReference = this.receiptPoReference.trim() || undefined;
      this.form.expiryDate = this.receiptExpiryDate || undefined;
      this.form.unitCost = this.receiptCostPerUnit ?? undefined;
      this.form.reason = 'RECEIPT';
    } else if (this.isAdjustment()) {
      // Reason code lives in `reason` since it's a free-text value, not a UUID.
      this.form.reason = `${this.adjustmentReasonCode}: ${this.adjustmentNotes.trim()}`;
    } else if (this.isTransfer()) {
      // Destination pharmacy IS a UUID, so the existing referenceId column fits.
      this.form.referenceId = this.transferDestinationPharmacyId;
      this.form.reason = `TRANSFER to ${this.transferDestinationName()}`;
    } else if (this.isReturn()) {
      // Return reference is a free-text supplier doc number, not a UUID — keep in reason.
      this.form.reason = `RETURN ref ${this.returnReference.trim()}`;
    }
  }

  private transferDestinationName(): string {
    const dest = this.pharmacies().find((p) => p.id === this.transferDestinationPharmacyId);
    return dest?.name ?? this.transferDestinationPharmacyId;
  }

  getItemName(item: InventoryItemResponse): string {
    return (
      item.medicationName ?? item.medicationCode ?? this.translate.instant('PHARMACY.UNKNOWN_ITEM')
    );
  }

  /**
   * QA D158: the sign a ledger row reads with. Receipts and returns add,
   * an adjustment carries its own sign, everything else takes stock off.
   * The old list named types that do not exist (RECEIVE, ADJUSTMENT_IN…),
   * so every receipt read as negative.
   */
  signedQuantity(tx: StockTransactionResponse): string {
    const q = Number(tx.quantity);
    const magnitude = Math.abs(q);
    if (tx.transactionType === 'ADJUSTMENT') return (q < 0 ? '-' : '+') + magnitude;
    if (tx.transactionType === 'RECEIPT' || tx.transactionType === 'RETURN') return '+' + magnitude;
    return '-' + magnitude;
  }

  getTypeClass(type: string): string {
    if (['RECEIPT', 'RETURN'].includes(type)) return 'tx-in';
    if (['ADJUSTMENT'].includes(type)) return 'tx-neutral';
    return 'tx-out';
  }

  private emptyForm(): StockTransactionRequest {
    return {
      inventoryItemId: '',
      transactionType: '',
      quantity: 0,
      reason: '',
      referenceId: '',
    };
  }
}
