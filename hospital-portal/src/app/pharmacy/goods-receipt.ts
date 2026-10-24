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

@Component({
  selector: 'app-goods-receipt',
  standalone: true,
  imports: [ServerTimePipe, CommonModule, FormsModule, TranslateModule, CfaPipe],
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

  private loadRecentLots(): void {
    this.lotsLoading.set(true);
    // Load lots for each inventory item — for display we use the first inventory item
    // In practice, get the most recent stock lots across items for this pharmacy
    this.svc.listInventoryByPharmacy(this.selectedPharmacyId, 0, 10).subscribe({
      next: (res) => {
        const items = res?.data?.content ?? [];
        if (items.length > 0) {
          // Load lots from the first few items
          this.loadLotsForItems(items.slice(0, 5));
        } else {
          this.recentLots.set([]);
          this.lotsLoading.set(false);
        }
      },
      error: () => {
        this.lotsLoading.set(false);
      },
    });
  }

  private loadLotsForItems(items: InventoryItemResponse[]): void {
    const allLots: StockLotResponse[] = [];
    let remaining = items.length;

    items.forEach((item) => {
      this.svc.listLotsByInventoryItem(item.id).subscribe({
        next: (res) => {
          const lots = res?.data?.content ?? [];
          allLots.push(...lots);
          remaining--;
          if (remaining === 0) {
            // Sort by received date desc
            allLots.sort((a, b) => (b.receivedDate ?? '').localeCompare(a.receivedDate ?? ''));
            this.recentLots.set(allLots.slice(0, 20));
            this.lotsLoading.set(false);
          }
        },
        error: () => {
          remaining--;
          if (remaining === 0) {
            this.recentLots.set(allLots);
            this.lotsLoading.set(false);
          }
        },
      });
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
        this.toast.error(err?.error?.message ?? this.translate.instant('PHARMACY.RECEIVE_FAILED'));
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
