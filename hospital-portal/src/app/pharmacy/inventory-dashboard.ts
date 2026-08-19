import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import {
  PharmacyService,
  PharmacyResponse,
  InventoryItemResponse,
  StockLotResponse,
  InventoryItemRequest,
  MedicationCatalogItemResponse,
} from '../services/pharmacy.service';

@Component({
  selector: 'app-inventory-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './inventory-dashboard.html',
  styleUrl: './inventory-dashboard.scss',
})
export class InventoryDashboardComponent implements OnInit {
  private readonly svc = inject(PharmacyService);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);

  activeTab = signal<'stock' | 'alerts' | 'expiry'>('stock');

  // Pharmacy selection
  pharmacies = signal<PharmacyResponse[]>([]);
  selectedPharmacyId = '';

  // Stock levels
  inventoryItems = signal<InventoryItemResponse[]>([]);
  stockLoading = signal(false);
  currentPage = 0;
  pageSize = 20;
  totalPages = 0;

  // Reorder alerts
  alertItems = signal<InventoryItemResponse[]>([]);
  alertsLoading = signal(false);

  // Expiring lots
  expiringLots = signal<StockLotResponse[]>([]);
  expiryLoading = signal(false);
  daysAhead = 90;

  // Create inventory item modal
  showCreateModal = signal(false);
  saving = signal(false);
  medications = signal<MedicationCatalogItemResponse[]>([]);
  createForm: InventoryItemRequest = this.emptyForm();

  ngOnInit(): void {
    this.loadPharmacies();
  }

  private loadPharmacies(): void {
    this.svc.listPharmacies(0, 100).subscribe({
      next: (page) => {
        this.pharmacies.set(page?.content ?? []);
        const list = page?.content ?? [];
        if (list.length > 0) {
          this.selectedPharmacyId = list[0].id;
          this.loadTabData();
        }
      },
    });
  }

  onPharmacyChange(): void {
    this.currentPage = 0;
    this.loadTabData();
  }

  setTab(tab: 'stock' | 'alerts' | 'expiry'): void {
    this.activeTab.set(tab);
    this.loadTabData();
  }

  private loadTabData(): void {
    if (!this.selectedPharmacyId) return;
    const tab = this.activeTab();
    if (tab === 'stock') this.loadStockLevels();
    else if (tab === 'alerts') this.loadReorderAlerts();
    else this.loadExpiringLots();
  }

  loadStockLevels(): void {
    this.stockLoading.set(true);
    this.svc
      .listInventoryByPharmacy(this.selectedPharmacyId, this.currentPage, this.pageSize)
      .subscribe({
        next: (res) => {
          const page = res?.data;
          this.inventoryItems.set(page?.content ?? []);
          this.totalPages = page?.totalPages ?? 0;
          this.stockLoading.set(false);
        },
        error: () => {
          this.toast.error('Failed to load inventory');
          this.stockLoading.set(false);
        },
      });
  }

  loadReorderAlerts(): void {
    this.alertsLoading.set(true);
    this.svc.getReorderAlertsByPharmacy(this.selectedPharmacyId).subscribe({
      next: (res) => {
        this.alertItems.set(res?.data ?? []);
        this.alertsLoading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load reorder alerts');
        this.alertsLoading.set(false);
      },
    });
  }

  loadExpiringLots(): void {
    this.expiryLoading.set(true);
    this.svc.getExpiringSoon(this.selectedPharmacyId, this.daysAhead).subscribe({
      next: (res) => {
        this.expiringLots.set(res?.data ?? []);
        this.expiryLoading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load expiring lots');
        this.expiryLoading.set(false);
      },
    });
  }

  triggerReorderAlerts(): void {
    this.svc.triggerReorderAlerts().subscribe({
      next: () => this.toast.success('Reorder alerts triggered'),
      error: () => this.toast.error('Failed to trigger reorder alerts'),
    });
  }

  prevPage(): void {
    if (this.currentPage > 0) {
      this.currentPage--;
      this.loadStockLevels();
    }
  }

  nextPage(): void {
    if (this.currentPage < this.totalPages - 1) {
      this.currentPage++;
      this.loadStockLevels();
    }
  }

  // Create inventory item
  openCreateItem(): void {
    this.createForm = this.emptyForm();
    this.createForm.pharmacyId = this.selectedPharmacyId;
    this.loadMedications();
    this.showCreateModal.set(true);
  }

  private loadMedications(): void {
    this.svc.listMedications(0, 200).subscribe({
      next: (page) => this.medications.set(page?.content ?? []),
    });
  }

  closeCreateModal(): void {
    this.showCreateModal.set(false);
  }

  submitCreateForm(): void {
    if (!this.createForm.pharmacyId || !this.createForm.medicationCatalogItemId) {
      this.toast.error('Pharmacy and medication are required');
      return;
    }
    this.saving.set(true);
    this.svc.createInventoryItem(this.createForm).subscribe({
      next: () => {
        this.toast.success('Inventory item created');
        this.closeCreateModal();
        this.saving.set(false);
        this.loadStockLevels();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Failed to create inventory item');
        this.saving.set(false);
      },
    });
  }

  getStockStatusClass(item: InventoryItemResponse): string {
    if (item.quantityOnHand <= 0) return 'stock-out';
    if (item.quantityOnHand <= item.reorderThreshold) return 'stock-low';
    return 'stock-ok';
  }

  getStockStatusLabel(item: InventoryItemResponse): string {
    if (item.quantityOnHand <= 0) return 'OUT OF STOCK';
    if (item.quantityOnHand <= item.reorderThreshold) return 'LOW';
    return 'IN STOCK';
  }

  getDaysUntilExpiry(expiryDate: string): number {
    const expiry = new Date(expiryDate);
    const today = new Date();
    return Math.ceil((expiry.getTime() - today.getTime()) / (1000 * 60 * 60 * 24));
  }

  private emptyForm(): InventoryItemRequest {
    return {
      pharmacyId: '',
      medicationCatalogItemId: '',
      quantityOnHand: 0,
      reorderThreshold: 10,
      reorderQuantity: 50,
      unit: 'UNIT',
      active: true,
    };
  }
}
