import { map } from 'rxjs';
import { allRows } from '../shared/all-pages';
import { refusalReason } from '../shared/refusal-reason';
import {
  Component,
  ElementRef,
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
  StockLotResponse,
  StockWitnessCandidate,
} from '../services/pharmacy.service';
import { CfaPipe } from '../shared/pipes/cfa.pipe';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { localDateString } from '../shared/date-utils';
import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { blankFields, refuseFields } from '../shared/forms/invalid-fields';
import { DashPipe } from '../shared/pipes/dash.pipe';
import { missingRequiredMessage } from '../shared/required-fields';

/** QA D1233: how many recent lots the receipt page lists. */
const RECENT_LOTS = 20;
/** Newest received first; same-day receipts by when they were recorded. */
const RECENT_LOTS_SORT = ['receivedDate,desc', 'createdAt,desc'] as const;

/** Every field the receipt form may refuse (QA D1306). */
const GR_FIELD_IDS = ['grItem', 'grLot', 'grExpiry', 'grQty', 'grWitness', 'grCost'];

@Component({
  selector: 'app-goods-receipt',
  standalone: true,
  imports: [
    DashPipe,
    SubmitGuardDirective,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    CfaPipe,
  ],
  templateUrl: './goods-receipt.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './goods-receipt.scss',
})
export class GoodsReceiptComponent implements OnInit {
  private readonly svc = inject(PharmacyService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  pharmacies = signal<PharmacyResponse[]>([]);
  /** True once the pharmacy list answered, so an empty list shows a hint, not a blank select (D59). */
  pharmaciesLoaded = signal(false);
  selectedPharmacyId = '';
  inventoryItems = signal<InventoryItemResponse[]>([]);

  private scopeVersion = 0;
  private lotsVersion = 0;
  private itemsVersion = 0;
  pharmacyFailed = signal(false);
  lotsFailed = signal(false);
  itemsFailed = signal(false);
  readonly recentLotsLimit = RECENT_LOTS;
  searchTerm = '';
  visibleLots(): StockLotResponse[] {
    const query = this.searchTerm.trim().toLocaleLowerCase();
    return this.recentLots().filter(
      (lot) =>
        !query ||
        [lot.lotNumber, lot.supplier, this.lotMedication(lot)].some((value) =>
          value?.toLocaleLowerCase().includes(query),
        ),
    );
  }
  lotMedication(lot: StockLotResponse): string {
    const item = this.inventoryItems().find((entry) => entry.id === lot.inventoryItemId);
    return item ? this.getItemName(item) : this.translate.instant('PHARMACY.UNKNOWN_ITEM');
  }
  // Recent lots
  recentLots = signal<StockLotResponse[]>([]);
  lotsLoading = signal(false);

  // Form
  showForm = signal(false);
  saving = signal(false);
  selectedInventoryItemId = '';
  form = this.emptyForm();
  /** QA D655: colleagues who may witness the receipt of a controlled substance. */
  witnessCandidates = signal<StockWitnessCandidate[]>([]);

  ngOnInit(): void {
    this.loadPharmacies();
    this.svc.listWitnessCandidates().subscribe({
      next: (res) => this.witnessCandidates.set(res?.data ?? []),
      error: () => this.witnessCandidates.set([]),
    });
  }

  /** QA D655: a controlled substance is received in front of a witness. */
  needsWitness(): boolean {
    return !!this.inventoryItems().find((i) => i.id === this.selectedInventoryItemId)?.controlled;
  }

  loadPharmacies(): void {
    this.pharmacyFailed.set(false);
    // Keep the upstream complete pharmacy selector (QA D1347).
    allRows((page, size) => this.svc.listPharmacies(page, size)).subscribe({
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
    this.itemsFailed.set(false);
    this.recentLots.set([]);
    this.searchTerm = '';
    this.closeForm();
    if (!this.selectedPharmacyId) return;
    this.loadInventoryItems();
    this.loadRecentLots();
  }

  loadInventoryItems(): void {
    const scope = this.scopeVersion;
    const version = ++this.itemsVersion;
    const pharmacyId = this.selectedPharmacyId;
    this.itemsFailed.set(false);
    allRows((page, size) =>
      this.svc.listInventoryByPharmacy(pharmacyId, page, size).pipe(map((r) => r?.data)),
    ).subscribe({
      next: (items) => {
        if (scope !== this.scopeVersion || version !== this.itemsVersion) return;
        this.inventoryItems.set(items);
      },
      error: () => {
        if (scope !== this.scopeVersion || version !== this.itemsVersion) return;
        this.itemsFailed.set(true);
      },
    });
  }

  /** Keep QA D1233: newest lots across every pharmacy item in one server-sorted read. */
  loadRecentLots(): void {
    const scope = this.scopeVersion;
    const version = ++this.lotsVersion;
    this.lotsLoading.set(true);
    this.lotsFailed.set(false);
    this.recentLots.set([]);
    this.svc
      .listLotsByPharmacy(this.selectedPharmacyId, 0, RECENT_LOTS, RECENT_LOTS_SORT)
      .subscribe({
        next: (res) => {
          if (scope !== this.scopeVersion || version !== this.lotsVersion) return;
          this.recentLots.set(res?.data?.content ?? []);
          this.lotsLoading.set(false);
        },
        error: () => {
          if (scope !== this.scopeVersion || version !== this.lotsVersion) return;
          this.lotsFailed.set(true);
          this.lotsLoading.set(false);
        },
      });
  }

  openReceiveForm(): void {
    this.form = this.emptyForm();
    this.selectedInventoryItemId = '';
    this.showForm.set(true);
  }

  closeForm(): void {
    this.showForm.set(false);
  }

  submitForm(): void {
    // QA D1306 (round 28): every refusal marks its field, not only a toast.
    const root = this.host.nativeElement;
    if (!this.selectedInventoryItemId) {
      const message = this.translate.instant('PHARMACY.SELECT_ITEM_REQUIRED');
      refuseFields(root, ['grItem'], GR_FIELD_IDS, message);
      this.toast.error(message);
      return;
    }
    // QA D143: a quantity that is present but not positive gets its own
    // message, not "required fields", which sent the user hunting.
    if (this.form.initialQuantity != null && this.form.initialQuantity <= 0) {
      const message = this.translate.instant('PHARMACY.RECEIPT_QUANTITY_POSITIVE');
      refuseFields(root, ['grQty'], GR_FIELD_IDS, message);
      this.toast.error(message);
      return;
    }
    // QA D778: a purchase price is never negative (the server refuses it too).
    if (this.form.unitCost != null && this.form.unitCost < 0) {
      const message = this.translate.instant('PHARMACY.UNIT_COST_NEGATIVE');
      refuseFields(root, ['grCost'], GR_FIELD_IDS, message);
      this.toast.error(message);
      return;
    }
    const blankLot = blankFields(
      {
        grLot: this.form.lotNumber,
        grExpiry: this.form.expiryDate,
        grQty: this.form.initialQuantity,
      },
      ['grLot', 'grExpiry', 'grQty'],
    );
    if (blankLot.length > 0) {
      // QA D1358: name the blank fields, not a fixed list.
      const message =
        missingRequiredMessage(this.translate, [
          { labelKey: 'PHARMACY.LOT_NUMBER', value: this.form.lotNumber },
          { labelKey: 'PHARMACY.EXPIRY_DATE', value: this.form.expiryDate },
          { labelKey: 'PHARMACY.QUANTITY_RECEIVED', value: this.form.initialQuantity },
        ]) ?? this.translate.instant('COMMON.SUBMIT_BLOCKED_REQUIRED');
      refuseFields(root, blankLot, GR_FIELD_IDS, this.translate.instant('COMMON.FIELD_REQUIRED'));
      this.toast.error(message);
      return;
    }

    if (this.needsWitness() && !this.form.witnessedBy) {
      const message = this.translate.instant('PHARMACY.WITNESS_REQUIRED');
      refuseFields(root, ['grWitness'], GR_FIELD_IDS, message);
      this.toast.error(message);
      return;
    }
    refuseFields(root, [], GR_FIELD_IDS, '');

    this.saving.set(true);
    const req = {
      ...this.form,
      inventoryItemId: this.selectedInventoryItemId,
      witnessedBy: this.needsWitness() ? this.form.witnessedBy : undefined,
    };
    this.svc.receiveStock(req).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('PHARMACY.LOT_RECEIVED'));
        this.closeForm();
        this.saving.set(false);
        this.loadRecentLots();
        this.loadInventoryItems();
      },
      error: (err) => {
        this.toast.error(refusalReason(err) ?? this.translate.instant('PHARMACY.RECEIVE_FAILED'));
        this.saving.set(false);
      },
    });
  }

  getItemName(item: InventoryItemResponse): string {
    return (
      item.medicationName ?? item.medicationCode ?? this.translate.instant('PHARMACY.UNKNOWN_ITEM')
    );
  }

  private emptyForm() {
    return {
      inventoryItemId: '',
      lotNumber: '',
      expiryDate: '',
      initialQuantity: 0,
      supplier: '',
      unitCost: 0,
      receivedDate: localDateString(),
      witnessedBy: undefined as string | undefined,
    };
  }
}
