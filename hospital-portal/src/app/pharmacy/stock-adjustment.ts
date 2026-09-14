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
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { ToastService } from '../core/toast.service';
import {
  PharmacyService,
  PharmacyResponse,
  InventoryItemResponse,
  StockTransactionRequest,
  StockTransactionResponse,
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
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './stock-adjustment.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './stock-adjustment.scss',
})
export class StockAdjustmentComponent implements OnInit {
  private readonly svc = inject(PharmacyService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  pharmacies = signal<PharmacyResponse[]>([]);
  selectedPharmacyId = '';
  inventoryItems = signal<InventoryItemResponse[]>([]);

  // Recent transactions
  transactions = signal<StockTransactionResponse[]>([]);
  txLoading = signal(false);

  // Filter
  filterType = '';
  readonly transactionTypes: TxType[] = ['RECEIPT', 'DISPENSE', 'ADJUSTMENT', 'TRANSFER', 'RETURN'];
  readonly adjustmentReasonCodes = ADJUSTMENT_REASON_CODES;

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
    this.showForm.set(true);
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
    if (!this.form.inventoryItemId || !this.txType() || this.form.quantity <= 0) {
      this.toast.error(this.translate.instant('PHARMACY.TX_REQUIRED_FIELDS'));
      return;
    }

    const validationErrorKey = this.validateTyped();
    if (validationErrorKey) {
      this.toast.error(this.translate.instant(validationErrorKey));
      return;
    }

    this.applyTypedFieldsToForm();

    this.saving.set(true);
    this.svc.recordTransaction(this.form).subscribe({
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
