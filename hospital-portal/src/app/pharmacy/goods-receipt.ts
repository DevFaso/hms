import { refusalReason } from '../shared/refusal-reason';
import { Component, inject, OnInit, signal, ChangeDetectionStrategy } from '@angular/core';
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

/** QA D1233: how many recent lots the receipt page lists. */
const RECENT_LOTS = 20;
/** Newest received first; same-day receipts by when they were recorded. */
const RECENT_LOTS_SORT = ['receivedDate,desc', 'createdAt,desc'] as const;

@Component({
  selector: 'app-goods-receipt',
  standalone: true,
  imports: [
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

  pharmacies = signal<PharmacyResponse[]>([]);
  /** True once the pharmacy list answered, so an empty list shows a hint, not a blank select (D59). */
  pharmaciesLoaded = signal(false);
  selectedPharmacyId = '';
  inventoryItems = signal<InventoryItemResponse[]>([]);

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
    this.loadRecentLots();
  }

  private loadInventoryItems(): void {
    this.svc.listInventoryByPharmacy(this.selectedPharmacyId, 0, 200).subscribe({
      next: (res) => {
        this.inventoryItems.set(res?.data?.content ?? []);
      },
    });
  }

  /**
   * QA D1233 (round 24): the newest lots received at the pharmacy, across
   * every item, in one read sorted by the server. This used to read the
   * first ten inventory items and the lots of the first FIVE of them, so a
   * lot of the sixth item (Artéméther-luméfantrine, just received) never
   * appeared.
   */
  private loadRecentLots(): void {
    this.lotsLoading.set(true);
    this.svc
      .listLotsByPharmacy(this.selectedPharmacyId, 0, RECENT_LOTS, RECENT_LOTS_SORT)
      .subscribe({
        next: (res) => {
          this.recentLots.set(res?.data?.content ?? []);
          this.lotsLoading.set(false);
        },
        error: () => {
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
    if (!this.selectedInventoryItemId) {
      this.toast.error(this.translate.instant('PHARMACY.SELECT_ITEM_REQUIRED'));
      return;
    }
    // QA D143: a quantity that is present but not positive gets its own
    // message, not "required fields", which sent the user hunting.
    if (this.form.initialQuantity != null && this.form.initialQuantity <= 0) {
      this.toast.error(this.translate.instant('PHARMACY.RECEIPT_QUANTITY_POSITIVE'));
      return;
    }
    // QA D778: a purchase price is never negative (the server refuses it too).
    if (this.form.unitCost != null && this.form.unitCost < 0) {
      this.toast.error(this.translate.instant('PHARMACY.UNIT_COST_NEGATIVE'));
      return;
    }
    if (!this.form.lotNumber || !this.form.expiryDate || !this.form.initialQuantity) {
      this.toast.error(this.translate.instant('PHARMACY.LOT_FIELDS_REQUIRED'));
      return;
    }

    if (this.needsWitness() && !this.form.witnessedBy) {
      this.toast.error(this.translate.instant('PHARMACY.WITNESS_REQUIRED'));
      return;
    }

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
