import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import { ToastService } from '../core/toast.service';
import {
  PharmacyService,
  PharmacyResponse,
  InventoryItemResponse,
  StockLotResponse,
} from '../services/pharmacy.service';

@Component({
  selector: 'app-goods-receipt',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './goods-receipt.html',
  styleUrl: './goods-receipt.scss',
})
export class GoodsReceiptComponent implements OnInit {
  private readonly svc = inject(PharmacyService);
  private readonly toast = inject(ToastService);

  pharmacies = signal<PharmacyResponse[]>([]);
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
      this.toast.error('Select an inventory item');
      return;
    }
    if (!this.form.lotNumber || !this.form.expiryDate || this.form.initialQuantity <= 0) {
      this.toast.error('Lot number, expiry date, and quantity are required');
      return;
    }

    this.saving.set(true);
    const req = { ...this.form, inventoryItemId: this.selectedInventoryItemId };
    this.svc.receiveStock(req).subscribe({
      next: () => {
        this.toast.success('Stock lot received successfully');
        this.closeForm();
        this.saving.set(false);
        this.loadRecentLots();
        this.loadInventoryItems();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Failed to receive stock');
        this.saving.set(false);
      },
    });
  }

  getItemName(item: InventoryItemResponse): string {
    return item.medicationName ?? item.medicationCode ?? 'Unknown item';
  }

  private emptyForm() {
    return {
      inventoryItemId: '',
      lotNumber: '',
      expiryDate: '',
      initialQuantity: 0,
      supplier: '',
      unitCost: 0,
    };
  }
}
