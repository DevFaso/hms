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

  private scopeVersion = 0;
  private lotsVersion = 0;
  pharmacyFailed = signal(false);
  lotsFailed = signal(false);
  lotsPartial = signal(false);
  sampledItems = signal<InventoryItemResponse[]>([]);
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
    const item = this.sampledItems().find((entry) => entry.id === lot.inventoryItemId);
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
      error: () => this.pharmacyFailed.set(true),
    });
  }

  onPharmacyChange(): void {
    this.scopeVersion++;
    this.inventoryItems.set([]);
    this.recentLots.set([]);
    this.sampledItems.set([]);
    this.searchTerm = '';
    this.closeForm();
    if (!this.selectedPharmacyId) return;
    this.loadInventoryItems();
    this.loadRecentLots();
  }

  private loadInventoryItems(): void {
    const scope = this.scopeVersion;
    this.svc.listInventoryByPharmacy(this.selectedPharmacyId, 0, 200).subscribe({
      next: (res) => {
        if (scope !== this.scopeVersion) return;
        this.inventoryItems.set(res?.data?.content ?? []);
      },
    });
  }

  loadRecentLots(): void {
    const scope = this.scopeVersion;
    const version = ++this.lotsVersion;
    this.lotsLoading.set(true);
    this.lotsFailed.set(false);
    this.lotsPartial.set(false);
    this.recentLots.set([]);
    this.svc.listInventoryByPharmacy(this.selectedPharmacyId, 0, 10).subscribe({
      next: (res) => {
        if (scope !== this.scopeVersion || version !== this.lotsVersion) return;
        const items = (res?.data?.content ?? []).slice(0, 5);
        this.sampledItems.set(items);
        if (items.length) this.loadLotsForItems(items, scope, version);
        else this.lotsLoading.set(false);
      },
      error: () => {
        if (scope !== this.scopeVersion || version !== this.lotsVersion) return;
        this.lotsFailed.set(true);
        this.lotsLoading.set(false);
      },
    });
  }

  private loadLotsForItems(items: InventoryItemResponse[], scope: number, version: number): void {
    const allLots: StockLotResponse[] = [];
    let remaining = items.length;
    let failures = 0;
    const complete = () => {
      if (scope !== this.scopeVersion || version !== this.lotsVersion) return;
      if (--remaining !== 0) return;
      allLots.sort((a, b) => (b.receivedDate ?? '').localeCompare(a.receivedDate ?? ''));
      this.recentLots.set(allLots.slice(0, 20));
      this.lotsFailed.set(failures === items.length);
      this.lotsPartial.set(failures > 0 && failures < items.length);
      this.lotsLoading.set(false);
    };
    items.forEach((item) =>
      this.svc.listLotsByInventoryItem(item.id).subscribe({
        next: (res) => {
          allLots.push(...(res?.data?.content ?? []));
          complete();
        },
        error: () => {
          failures++;
          complete();
        },
      }),
    );
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
